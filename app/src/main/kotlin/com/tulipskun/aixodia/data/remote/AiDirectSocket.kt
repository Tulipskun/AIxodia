package com.tulipskun.aixodia.data.remote

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.tulipskun.aixodia.ConnConfig
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
    private val _lastError = MutableStateFlow("")
    val lastError: StateFlow<String> = _lastError

    private val _frames = MutableSharedFlow<AiOutput>(extraBufferCapacity = 256)
    val frames: SharedFlow<AiOutput> = _frames

    private var ws: WebSocket? = null
    private var wantOpen = false
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

    fun close() {
        wantOpen = false
        ws?.close(1000, "ui")
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

    private fun describe(r: Response?, t: Throwable): String = when {
        r == null -> "เชื่อมต่อไม่ได้: ${t.message ?: t::class.simpleName}"
        r.code == 401 -> "401 token ไม่ผ่าน — daemon ไม่รับ D1 token นี้"
        r.code == 429 -> "429 ถูกล็อกชั่วคราวจากการเดา token ผิดเกิน 5 ครั้ง"
        r.code == 503 -> "503 daemon ตรวจ token ไม่ได้ชั่วคราว (Worker/D1 อาจล่ม)"
        else -> "HTTP ${r.code} ${r.message}".trim()
    }

    private suspend fun loop() {
        var backoff = 1000L
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
                        _lastError.value = ""
                        val hello = inAdapter.toJson(
                            AiInput(
                                type = "hello",
                                sessionId = session,
                                content = listOf(ContentPart(text = "resume:$resumeFrom")),
                            )
                        )
                        w.send(hello)
                        _state.value = ConnState.ONLINE
                        backoff = 1000L
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
                            _lastError.value = "ปิดการเชื่อมต่อ ($code) ${reason}".trim()
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
            delay(backoff + Random.nextLong(0, 400))
            backoff = min(backoff * 2, 30_000L)
        }
        _state.value = ConnState.OFFLINE
    }
}
