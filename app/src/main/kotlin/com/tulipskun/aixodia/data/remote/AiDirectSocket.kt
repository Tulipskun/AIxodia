package com.tulipskun.aixodia.data.remote

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.tulipskun.aixodia.ConnConfig
import com.tulipskun.aixodia.data.model.AiInput
import com.tulipskun.aixodia.data.model.AiOutput
import com.tulipskun.aixodia.data.model.ContentPart
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
 * Direct WebSocket to the ai daemon mobile endpoint (see bridge/mobile_ws.go).
 * JSON frames mirror ai sdk/io.go Input/Output. Reconnect uses exp backoff
 * like transport/discord/gateway_liveness.go.
 */
class AiDirectSocket(private val settings: com.tulipskun.aixodia.SettingsStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val outAdapter = moshi.adapter(AiOutput::class.java)
    private val inAdapter = moshi.adapter(AiInput::class.java)
    private val client = OkHttpClient()

    private val _state = MutableStateFlow(ConnState.OFFLINE)
    val state: StateFlow<ConnState> = _state

    private val _frames = MutableSharedFlow<AiOutput>(extraBufferCapacity = 128)
    val frames: SharedFlow<AiOutput> = _frames

    private var ws: WebSocket? = null
    private var wantOpen = false
    private var lastSeq: Long = 0
    private var cfg: ConnConfig? = null

    fun open(sessionId: String, fromSeq: Long) {
        lastSeq = fromSeq
        if (wantOpen) return
        wantOpen = true
        scope.launch { loop(sessionId) }
    }

    fun close() {
        wantOpen = false
        ws?.close(1000, "ui")
        ws = null
        _state.value = ConnState.OFFLINE
    }

    fun send(text: String) {
        val c = cfg ?: return
        val frame = AiInput(sessionId = c.sessionId, content = listOf(ContentPart(text = text)), token = c.token)
        ws?.send(inAdapter.toJson(frame))
    }

    private suspend fun loop(sessionId: String) {
        var backoff = 1000L
        while (wantOpen) {
            try {
                cfg = settings.current()
                val c = cfg!!
                _state.value = ConnState.CONNECTING
                val req = Request.Builder().url(c.wsUrl).build()
                val opened = kotlinx.coroutines.CompletableDeferred<Unit>()
                ws = client.newWebSocket(req, object : WebSocketListener() {
                    override fun onOpen(w: WebSocket, r: Response) {
                        // hello with resume cursor (AX-002)
                        val hello = inAdapter.toJson(
                            AiInput(type = "hello", sessionId = sessionId, token = c.token,
                                content = listOf(ContentPart(text = "resume:${lastSeq}")))
                        )
                        w.send(hello)
                        _state.value = ConnState.ONLINE
                        backoff = 1000L
                        opened.complete(Unit)
                    }
                    override fun onMessage(w: WebSocket, text: String) {
                        try {
                            val f = outAdapter.fromJson(text) ?: return
                            if (f.seq > 0) lastSeq = maxOf(lastSeq, f.seq)
                            scope.launch { _frames.emit(f) }
                        } catch (_: Exception) { }
                    }
                    override fun onFailure(w: WebSocket, t: Throwable, r: Response?) {
                        if (!opened.isCompleted) opened.complete(Unit)
                        _state.value = ConnState.OFFLINE
                    }
                    override fun onClosed(w: WebSocket, code: Int, reason: String) {
                        _state.value = ConnState.OFFLINE
                    }
                })
                opened.await()
                // stay open until failure/close
                while (wantOpen && _state.value == ConnState.ONLINE) delay(1000)
            } catch (_: Exception) { }
            if (!wantOpen) break
            _state.value = ConnState.OFFLINE
            delay(backoff + Random.nextLong(0, 500))
            backoff = min(backoff * 2, 30_000L)
        }
    }
}
