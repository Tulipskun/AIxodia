package com.tulipskun.aixodia.data.remote

import android.util.Base64
import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.tulipskun.aixodia.data.model.AiOutput
import com.tulipskun.aixodia.R
import com.tulipskun.aixodia.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.random.Random

private const val TAG = "RelayClient"

/** One reply from the engine's HTTP API, carried over the relay. */
class RpcReply(
    val code: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
    val reason: String = "",
) {
    val isSuccessful: Boolean get() = code in 200..299
    fun text(): String = String(body, Charsets.UTF_8)
}

/** Callbacks for one terminal stream. */
interface TermStream {
    fun onText(json: String)
    fun onBytes(bytes: ByteArray)
    fun onClosed()
}

/** Which vocabulary an inbound frame speaks. */
internal enum class RelayFrameKind { CONTROL, RPC, TERM, CHAT }

/**
 * Two vocabularies arrive on this socket and they do not mix.
 *
 * Control frames carry "ctl" ({"ctl":"presence","engine":true} when the socket
 * pairs, {"ctl":"superseded-phone"} when a former relay refused the other
 * phone), while the engine's frames carry "ch" ({"ch":"chat"...},
 * {"ch":"rpc"...}, {"ch":"term"...}). A frame with neither is a chat frame,
 * because that is what the engine sends for the streamed answer.
 *
 * Reading only "ch" is how a phone ends up connected but never ONLINE: the
 * presence frame has no "ch", so it looks like a chat frame, the engine is
 * never noticed, and the chat hello is never sent.
 */
internal fun relayFrameKind(ctl: String?, ch: String?): RelayFrameKind {
    if (!ctl.isNullOrEmpty()) return RelayFrameKind.CONTROL
    return when (ch) {
        "rpc" -> RelayFrameKind.RPC
        "term" -> RelayFrameKind.TERM
        else -> RelayFrameKind.CHAT
    }
}

/**
 * The app's one connection: every stream to the engine travels here.
 *
 * It dials the engine's `/room` socket on the current tunnel URL. Chat frames,
 * the terminal's bytes and the multiplexed terminal control frames all share
 * the socket, so a phone needs one handshake, one token and one reconnect loop.
 * The engine answers HTTP calls as rpc frames on the same socket, which is why
 * HistoryApi's plain requests no longer need a transport of their own.
 */
class RelayClient(private val settings: SettingsStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        // A ping every ten seconds fails a socket the network dropped within
        // about one interval, instead of the twenty seconds (plus the timeout
        // after it) that made a reconnect feel slow.
        .pingInterval(java.time.Duration.ofSeconds(10))
        .connectTimeout(java.time.Duration.ofSeconds(8))
        // Liveness for a long-lived socket is the pings, not the read timeout:
        // a read timeout here would tear down an idle-but-healthy link.
        .readTimeout(java.time.Duration.ZERO)
        .build()
    private val outAdapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        .adapter(AiOutput::class.java)

    private val _state = MutableStateFlow(ConnState.OFFLINE)
    val state: StateFlow<ConnState> = _state

    private val _lastError = MutableStateFlow(0)
    val lastError: StateFlow<Int> = _lastError

    // Chat frames keep the same contract AiDirectSocket had: arrival order is
    // what the streamed answer depends on, so they are emitted from the socket
    // callback thread in order and a full buffer drops the oldest.
    private val _frames = MutableSharedFlow<AiOutput>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val chatFrames: SharedFlow<AiOutput> = _frames

    /** Called each time the socket comes up, after the state flips online. */
    @Volatile
    var onOpen: (() -> Unit)? = null

    // Whether the engine is paired with this room right now. The relay socket can
    // be up with no engine behind it (the kernel restarted), which is offline as
    // far as the app is concerned.
    private val _engine = MutableStateFlow(false)
    val enginePresent: StateFlow<Boolean> = _engine

    private val pending = ConcurrentHashMap<String, CompletableDeferred<RpcReply>>()
    private val streams = ConcurrentHashMap<Int, TermStream>()
    // Stream ids are 16-bit and only need to be unique to this socket. Starting
    // at a random point keeps a fresh app process from reusing ids the engine
    // still remembers from the previous one (it only drops them when its own
    // relay session restarts), which used to make the first few opens vanish.
    private val nextStream = AtomicInteger(1 + Random.nextInt(0xffff))

    @Volatile
    private var ws: WebSocket? = null

    @Volatile
    private var wantOpen = false

    @Volatile
    private var backoffMs = 1000L

    // Lets reconnect() cut a backoff sleep short. Without it a redial asked for
    // now still waited out the remaining delay — up to half a minute — which is
    // part of why a reconnect felt slow. Conflated: one pending wake is enough.
    private val wake = Channel<Unit>(Channel.CONFLATED)

    val isOnline: Boolean get() = _state.value == ConnState.ONLINE

    /** Starts the single connection (idempotent). */
    fun connect() {
        if (wantOpen) return
        wantOpen = true
        scope.launch { loop() }
    }

    fun disconnect() {
        wantOpen = false
        ws?.close(1000, "ui")
        ws = null
        lost("closed")
        _state.value = ConnState.OFFLINE
    }

    /** Drops the socket so the loop dials again immediately. */
    fun reconnect() {
        if (!wantOpen) return
        backoffMs = 1000L
        ws?.cancel()
        ws = null
        // The old socket's listener is stale from here on, so its close
        // callback will not run. Finish everything it owned now: a terminal
        // listener left waiting for an onClosed that can never arrive would
        // keep the panel on a dead stream instead of reattaching.
        lost("reconnecting")
        _state.value = ConnState.OFFLINE
        // Wake the loop if it is sleeping between attempts, so "immediately"
        // means immediately and not "after the rest of the backoff".
        wake.trySend(Unit)
    }

    /**
     * Re-dials at once when the link is down and leaves a healthy socket alone.
     * Called when the app returns to the foreground or the network comes back,
     * where a dead link should not have to be noticed by a timeout first.
     */
    fun reconnectIfDown() {
        if (!wantOpen || isOnline) return
        reconnect()
    }

    /** Sends one chat frame, adding the channel the engine routes on. */
    fun sendChat(json: String): Boolean {
        val w = ws ?: return false
        val text = runCatching { JSONObject(json).put("ch", "chat").toString() }
            .getOrElse { json }
        return w.send(text)
    }

    /**
     * Calls the engine's HTTP API over the socket. A body larger than one frame
     * is sent as base64 chunks the engine joins before dispatching.
     */
    suspend fun rpc(
        method: String,
        path: String,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
    ): RpcReply {
        val w = ws
        if (w == null || _state.value != ConnState.ONLINE) {
            return RpcReply(503, emptyMap(), ByteArray(0), "relay offline")
        }
        val rid = java.util.UUID.randomUUID().toString()
        val done = CompletableDeferred<RpcReply>()
        pending[rid] = done
        try {
            val chunks = mutableListOf<ByteArray>()
            if (body != null && body.isNotEmpty()) {
                var at = 0
                while (at < body.size) {
                    val end = min(at + CHUNK, body.size)
                    chunks.add(body.copyOfRange(at, end))
                    at = end
                }
            }
            for (i in 0 until chunks.size - 1) {
                val f = JSONObject()
                    .put("ch", "rpc").put("rid", rid)
                    .put("more", true)
                    .put("b", Base64.encodeToString(chunks[i], Base64.NO_WRAP))
                if (!w.send(f.toString())) return RpcReply(503, emptyMap(), ByteArray(0), "relay offline")
            }
            val last = JSONObject().put("ch", "rpc").put("rid", rid)
                .put("m", method).put("p", path)
            if (headers.isNotEmpty()) last.put("h", JSONObject(headers as Map<*, *>))
            if (chunks.isNotEmpty()) {
                last.put("b", Base64.encodeToString(chunks.last(), Base64.NO_WRAP))
            }
            if (!w.send(last.toString())) return RpcReply(503, emptyMap(), ByteArray(0), "relay offline")
            return withTimeoutOrNull(180_000) { done.await() }
                ?: RpcReply(504, emptyMap(), ByteArray(0), "relay timed out")
        } finally {
            pending.remove(rid)
        }
    }

    /** Opens one terminal stream; the returned id addresses its bytes. */
    fun openTerminal(id: String, cols: Int, rows: Int, listener: TermStream): Int? {
        val w = ws ?: return null
        val sid = nextStream.getAndIncrement() and 0xffff
        streams[sid] = listener
        val frame = JSONObject()
            .put("ch", "ctl").put("act", "open").put("id", id)
            .put("sid", sid).put("cols", cols).put("rows", rows)
        if (!w.send(frame.toString())) {
            streams.remove(sid)
            return null
        }
        return sid
    }

    fun termSend(sid: Int, bytes: ByteArray): Boolean {
        val w = ws ?: return false
        if (bytes.isEmpty()) return false
        val buf = ByteArray(3 + bytes.size)
        buf[0] = TERM_CHANNEL.toByte()
        buf[1] = ((sid shr 8) and 0xff).toByte()
        buf[2] = (sid and 0xff).toByte()
        System.arraycopy(bytes, 0, buf, 3, bytes.size)
        return w.send(buf.toByteString())
    }

    fun termJSON(sid: Int, json: String): Boolean {
        val w = ws ?: return false
        return w.send("""{"ch":"term","sid":$sid,"k":"json","p":$json}""")
    }

    fun closeTerminal(sid: Int) {
        streams.remove(sid)
        ws?.send("""{"ch":"ctl","act":"close","sid":$sid}""")
    }

    /** Tells the engine the app is done: running turns stop, the socket stays. */
    fun bye() {
        ws?.send("""{"ch":"ctl","act":"bye"}""")
    }

    private fun onText(text: String) {
        val o = runCatching { JSONObject(text) }.getOrNull()
        if (o == null) {
            _frames.tryEmit(AiOutput(kind = "error", text = "unreadable frame: ${text.take(120)}"))
            return
        }
        when (relayFrameKind(o.optString("ctl").ifEmpty { null }, o.optString("ch").ifEmpty { null })) {
            RelayFrameKind.CONTROL -> onCtl(o)
            RelayFrameKind.RPC -> onRpc(o)
            RelayFrameKind.TERM -> onTerm(o)
            RelayFrameKind.CHAT -> {
                val frame = runCatching { outAdapter.fromJson(text) }.getOrNull()
                _frames.tryEmit(
                    frame ?: AiOutput(kind = "error", text = "unreadable frame: ${text.take(120)}")
                )
            }
        }
    }

    private fun onCtl(o: JSONObject) {
        when (o.optString("ctl")) {
            "presence" -> {
                val here = o.optBoolean("engine", false)
                _engine.value = here
                _state.value = if (here) ConnState.ONLINE else ConnState.CONNECTING
                if (here) hello()
            }
            // A former relay closed this socket for another phone; the close
            // handler takes it from there. A direct socket never sends it.
            "superseded-phone" -> {
                Log.i(TAG, "another phone took the room")
                _engine.value = false
            }
            // `superseded` and `superseded-phone` close the socket right after;
            // the close handler takes it from there.
        }
    }

    /** Tells the engine the chat this phone is showing. Runs once per pairing. */
    private fun hello() {
        try {
            onOpen?.invoke()
        } catch (t: Throwable) {
            Log.w(TAG, "hello failed", t)
        }
    }

    private fun onRpc(o: JSONObject) {
        val done = pending.remove(o.optString("rid")) ?: return
        val headers = mutableMapOf<String, String>()
        o.optJSONObject("h")?.let { h ->
            for (k in h.keys()) headers[k] = h.optString(k)
        }
        val body = o.optString("b").let {
            if (it.isEmpty()) ByteArray(0) else Base64.decode(it, Base64.DEFAULT)
        }
        done.complete(RpcReply(o.optInt("s", 500), headers, body, o.optString("e")))
    }

    private fun onTerm(o: JSONObject) {
        val sid = o.optInt("sid", -1)
        val listener = streams[sid] ?: return
        when (o.optString("k")) {
            "b64" -> listener.onBytes(Base64.decode(o.optString("p"), Base64.DEFAULT))
            else -> listener.onText(o.opt("p")?.toString() ?: "{}")
        }
    }

    private fun onBinary(bytes: ByteString) {
        if (bytes.size < 3 || bytes[0].toInt() != TERM_CHANNEL) return
        val sid = ((bytes[1].toInt() and 0xff) shl 8) or (bytes[2].toInt() and 0xff)
        streams[sid]?.onBytes(bytes.substring(3).toByteArray())
    }

    /** Everything tied to this socket ends with it. */
    private fun lost(reason: String) {
        _engine.value = false
        if (pending.isNotEmpty()) {
            val failed = RpcReply(503, emptyMap(), ByteArray(0), reason)
            for (key in pending.keys) pending.remove(key)?.complete(failed)
        }
        if (streams.isNotEmpty()) {
            val listeners = streams.values.toList()
            streams.clear()
            listeners.forEach { it.onClosed() }
        }
    }

    private fun describe(r: Response?, t: Throwable): Int {
        Log.w(TAG, "relay socket failure", t)
        if (r != null) return when (r.code) {
            401 -> R.string.offline_token_rejected
            429 -> R.string.offline_rate_limited
            503 -> R.string.offline_token_unverified
            else -> R.string.offline_http
        }
        val cause = generateSequence(t) { it.cause }.last()
        return when (cause) {
            is java.net.UnknownHostException -> R.string.offline_unknown_host
            is java.net.ConnectException -> R.string.offline_refused
            is java.net.SocketTimeoutException -> R.string.offline_timeout
            is javax.net.ssl.SSLException -> R.string.offline_tls
            else -> R.string.offline_generic
        }
    }

    private suspend fun loop() {
        while (wantOpen) {
            var opened = false
            try {
                val c = settings.current()
                // The socket address always follows the daemon address, so a
                // stored wsUrl from an older build (the relay room) cannot
                // outlive the switch to the tunnel.
                val wsUrl = SettingsStore.wsUrlFor(c.daemonUrl).ifBlank { c.wsUrl }
                if (wsUrl.isBlank() || c.token.isBlank()) {
                    _state.value = ConnState.OFFLINE
                    delay(2000)
                    continue
                }
                _state.value = ConnState.CONNECTING
                val req = Request.Builder()
                    .url(wsUrl)
                    .header("Authorization", "Bearer ${c.token}")
                    .build()
                val ready = CompletableDeferred<Unit>()
                // Completed by a failure or a close: the loop waits on the socket
                // itself, not on the reported state, because losing the engine
                // (presence false) leaves the socket up and must not redial.
                val down = CompletableDeferred<Unit>()
                ws = client.newWebSocket(req, object : WebSocketListener() {
                    // A socket that has been replaced must not touch shared
                    // state: reconnect() cancels the old one and the loop dials a
                    // new one at once, so the old socket's teardown callbacks
                    // arrive after the new one exists. Letting them run would
                    // fail the new socket's rpc requests and kill its streams.
                    private fun stale(w: WebSocket): Boolean = ws !== w

                    override fun onOpen(w: WebSocket, r: Response) {
                        if (stale(w)) {
                            ready.complete(Unit)
                            return
                        }
                        _lastError.value = 0
                        // The relay only forwards this side's frames after it has
                        // been told which half of the room this is.
                        w.send("""{"role":"phone"}""")
                        _state.value = ConnState.CONNECTING
                        backoffMs = 1000L
                        ready.complete(Unit)
                    }

                    override fun onMessage(w: WebSocket, text: String) {
                        if (!stale(w)) onText(text)
                    }

                    override fun onMessage(w: WebSocket, bytes: ByteString) {
                        if (!stale(w)) onBinary(bytes)
                    }

                    override fun onFailure(w: WebSocket, t: Throwable, r: Response?) {
                        if (!ready.isCompleted) ready.complete(Unit)
                        down.complete(Unit)
                        if (stale(w)) return
                        _engine.value = false
                        _state.value = ConnState.OFFLINE
                        _lastError.value = describe(r, t)
                        lost("socket failed")
                    }

                    override fun onClosed(w: WebSocket, code: Int, reason: String) {
                        if (!ready.isCompleted) ready.complete(Unit)
                        down.complete(Unit)
                        if (stale(w)) return
                        _engine.value = false
                        _state.value = ConnState.OFFLINE
                        if (code == 1008 || reason.isNotEmpty()) {
                            _lastError.value = R.string.offline_closed
                        }
                        lost("socket closed")
                    }

                    override fun onClosing(w: WebSocket, code: Int, reason: String) {
                        w.close(code, reason)
                    }
                })
                ready.await()
                opened = true
                down.await()
            } catch (_: Exception) {
                // fall through to backoff
            }
            ws?.close(1000, "reconnect")
            ws = null
            if (!opened) {
                _state.value = ConnState.OFFLINE
            }
            if (!wantOpen) break
            _state.value = ConnState.OFFLINE
            // Wait out the backoff, but let reconnect() end the wait early.
            withTimeoutOrNull(backoffMs + Random.nextLong(0, 400)) { wake.receive() }
            backoffMs = min(backoffMs * 2, 8_000L)
        }
        _state.value = ConnState.OFFLINE
    }

    private companion object {
        /** Mirrors the engine's binary channel byte for terminal streams. */
        const val TERM_CHANNEL = 2

        /** Body chunk per rpc frame; well under the relay's frame limit. */
        const val CHUNK = 192 * 1024
    }
}
