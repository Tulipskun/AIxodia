package com.tulipskun.aixodia.data.remote

import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.tulipskun.aixodia.ConnConfig
import com.tulipskun.aixodia.R
import com.tulipskun.aixodia.SettingsStore
import com.tulipskun.aixodia.data.model.AiInput
import com.tulipskun.aixodia.data.model.AiOutput
import com.tulipskun.aixodia.data.model.ContentPart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.math.min
import kotlin.random.Random

enum class ConnState { OFFLINE, CONNECTING, ONLINE }

/**
 * One WebSocket for the whole app (not per session). Frames carry
 * `session_id`, so switching sessions only changes which rows the repository
 * writes — the connection and its auth stay up.
 *
 * Auth is connection-scoped: the token travels in the hello frame only. The
 * daemon keeps working when this socket is gone; on reconnect the app pulls the
 * newest turns from the DB and then takes the live tail again.
 */
private const val TAG = "AiDirectSocket"

class AiDirectSocket(private val settings: SettingsStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val outAdapter = moshi.adapter(AiOutput::class.java)
    private val inAdapter = moshi.adapter(AiInput::class.java)
    private val client = OkHttpClient.Builder()
        .pingInterval(java.time.Duration.ofSeconds(20))
        .build()

    private val _state = MutableStateFlow(ConnState.OFFLINE)
    val state: StateFlow<ConnState> = _state

    /** Human-readable reason for the current state (e.g. "401 token ไม่ผ่าน"). */
    private val _lastError = MutableStateFlow(0)
    /** A string resource id, so the wording stays in strings.xml. */
    val lastError: StateFlow<Int> = _lastError

    private val _frames = MutableSharedFlow<AiOutput>(extraBufferCapacity = 256)
    val frames: SharedFlow<AiOutput> = _frames

    private var ws: WebSocket? = null
    private var wantOpen = false

    // Reconnect delay, owned by the loop but resettable from outside: a known
    // address change should not sit behind a backoff grown by a dead daemon.
    @Volatile
    private var backoffMs = 1000L
    private var session: String = "default"
    private var resumeFrom: Long = 0

    /** Starts the single connection (idempotent) for [sessionId]. */
    fun open(sessionId: String, fromSeq: Long) {
        session = sessionId
        resumeFrom = fromSeq
        if (wantOpen) return
        wantOpen = true
        scope.launch { loop() }
    }

    /** Switches the session used for subsequent sends + reconnects. */
    fun switchSession(sessionId: String) {
        session = sessionId
    }

    /**
     * The stop button: asks the daemon to cancel the turn in flight for this
     * chat. Returns false when the socket is down, in which case there is
     * nothing running to stop anyway.
     */
    fun cancel(sessionId: String): Boolean {
        val frame = AiInput(type = "cancel", sessionId = sessionId)
        return ws?.send(inAdapter.toJson(frame)) == true
    }

    /**
     * Stops one sub agent job and leaves the turn running. The daemon answers
     * with a `done` frame that carries the job id and what it did, so the phone
     * updates that row instead of ending the thread.
     */
    fun cancelSubAgent(sessionId: String, jobId: String): Boolean {
        if (jobId.isBlank()) return false
        val frame = AiInput(type = "cancel", sessionId = sessionId, jobId = jobId)
        return ws?.send(inAdapter.toJson(frame)) == true
    }

    fun close() {
        wantOpen = false
        ws?.close(1000, "ui")
        ws = null
        _state.value = ConnState.OFFLINE
    }

    /**
     * Drops the current socket so [loop] dials again immediately, rather than
     * waiting out the backoff.
     *
     * Needed because the daemon address changes on its own: the URL is random
     * per kernel boot, so after a redeploy the phone is holding an address that
     * no longer answers. The backoff can be up to 30 seconds, which is long
     * enough that the chat looks broken right after a restart even though the
     * new address is already known.
     */
    fun reconnect() {
        if (!wantOpen) return
        backoffMs = 1000L
        ws?.cancel()
        ws = null
        _state.value = ConnState.OFFLINE
    }

    /** Returns true when the frame was handed to the socket. */
    fun send(sessionId: String, text: String, clientMsgId: String): Boolean {
        val frame = AiInput(
            sessionId = sessionId,
            content = listOf(ContentPart(text = text)),
            clientMsgId = clientMsgId,
        )
        return ws?.send(inAdapter.toJson(frame)) == true
    }

    /**
     * The banner sits under the composer, so it has to be a sentence a reader
     * can act on. A raw `UnknownHostException: Unable to resolve host
     * "draw-power-…trycloudflare.com"` filled three lines and leaked the tunnel
     * hostname, so the cause is classified here, the wording lives in
     * strings.xml, and the full text goes to logcat where it is actually useful.
     *
     * The reason is a resource id rather than a string because it has to be
     * translatable, and because the classification is the part worth testing —
     * not the English.
     */
    private fun describe(r: Response?, t: Throwable): Int {
        Log.w(TAG, "socket failure", t)
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
            // Not a class name: `GaiException` is a Java-internal name and reads
            // as noise. The throwable is already in logcat above.
            else -> R.string.offline_generic
        }
    }

    private suspend fun loop() {
        while (wantOpen) {
            var opened = false
            try {
                val c: ConnConfig = settings.current()
                if (c.wsUrl.isBlank() || c.token.isBlank()) {
                    // Nothing configured yet (runtime settings are empty on a
                    // fresh install) — stay offline quietly instead of retrying
                    // an invalid URL forever.
                    _state.value = ConnState.OFFLINE
                    delay(2000)
                    continue
                }
                _state.value = ConnState.CONNECTING
                // The only token in the system travels here: the D1 access
                // token, in the handshake header. Frames stay token-free.
                val req = Request.Builder()
                    .url(c.wsUrl)
                    .header("Authorization", "Bearer ${c.token}")
                    .build()
                val ready = CompletableDeferred<Unit>()
                ws = client.newWebSocket(req, object : WebSocketListener() {
                    override fun onOpen(w: WebSocket, r: Response) {
                        _lastError.value = 0
                        val hello = inAdapter.toJson(
                            AiInput(
                                type = "hello",
                                sessionId = session,
                                content = listOf(ContentPart(text = "resume:$resumeFrom")),
                            )
                        )
                        w.send(hello)
                        _state.value = ConnState.ONLINE
                        backoffMs = 1000L
                        ready.complete(Unit)
                    }

                    override fun onMessage(w: WebSocket, text: String) {
                        try {
                            val f = outAdapter.fromJson(text) ?: return
                            scope.launch { _frames.emit(f) }
                        } catch (_: Exception) {
                            scope.launch {
                                _frames.emit(
                                    AiOutput(
                                        kind = "error",
                                        text = "frame ที่อ่านไม่ได้: ${text.take(120)}",
                                    )
                                )
                            }
                        }
                    }

                    override fun onFailure(w: WebSocket, t: Throwable, r: Response?) {
                        if (!ready.isCompleted) ready.complete(Unit)
                        _state.value = ConnState.OFFLINE
                        _lastError.value = describe(r, t)
                    }

                    override fun onClosed(w: WebSocket, code: Int, reason: String) {
                        _state.value = ConnState.OFFLINE
                        if (code == 1008 || reason.isNotEmpty()) {
                            _lastError.value = R.string.offline_closed
                        }
                    }

                    override fun onClosing(w: WebSocket, code: Int, reason: String) {
                        w.close(code, reason)
                    }
                })
                ready.await()
                opened = true
                while (wantOpen && _state.value == ConnState.ONLINE) delay(500)
            } catch (_: Exception) {
                // fall through to backoff
            }
            if (!opened && _state.value != ConnState.ONLINE) {
                _state.value = ConnState.OFFLINE
            }
            if (!wantOpen) break
            _state.value = ConnState.OFFLINE
            delay(backoffMs + Random.nextLong(0, 400))
            backoffMs = min(backoffMs * 2, 30_000L)
        }
        _state.value = ConnState.OFFLINE
    }
}
