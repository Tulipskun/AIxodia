package com.tulipskun.aixodia.data.remote

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.tulipskun.aixodia.SettingsStore
import com.tulipskun.aixodia.data.model.AiOutput
import com.tulipskun.aixodia.data.model.AttachmentRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "RunSocket"

/**
 * The daemon heartbeats a quiet run stream every 15s (`: ping`). Reads are
 * still timed out so a request a dead tunnel never answers cannot hold the
 * turn forever.
 */
internal const val STREAM_READ_TIMEOUT_MS = 45_000L

/**
 * How long to wait between polls. A poll that carried events goes straight
 * back for the next slice, so text appears at the cadence it is produced; an
 * empty one waits a beat rather than spending the battery on a silent turn.
 */
internal const val POLL_HOT_MS = 60L
internal const val POLL_IDLE_MS = 300L

/**
 * Chat over POST /v1/runs + short polls of GET /v1/runs/{id}/events.
 *
 * The daemon also offers a live SSE stream, but Cloudflare Quick Tunnels
 * buffer `text/event-stream` until the origin closes it: through the tunnel a
 * held stream delivered the whole turn in one burst at the end, so the phone
 * showed nothing while it was being written. A poll with the sequence cursor
 * rides an ordinary request/response, which the tunnel passes through, and
 * each answer carries everything produced since the caller's last sequence.
 *
 * A run is created once, then viewed. Closing the app (or this socket) does not
 * stop the agent: the next [open] finds the remembered run id, asks the daemon
 * for its state, and resumes from the last sequence this process applied. The
 * final rows also land in history, so a missed tail is repaired by the
 * repository's normal D1 refresh.
 */
class RunSocket(
    private val settings: SettingsStore,
    context: Context,
) : ChatTransport {
    private val app = context.applicationContext
    private val cursor: SharedPreferences =
        app.getSharedPreferences("aixodia_run_cursor", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(ConnState.OFFLINE)
    override val state: StateFlow<ConnState> = _state.asStateFlow()
    override val lastError = MutableStateFlow(0)

    private val _frames = MutableSharedFlow<AiOutput>(extraBufferCapacity = 256)
    override val frames: SharedFlow<AiOutput> = _frames.asSharedFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // A turn's first token can outlast any ordinary HTTP read timeout, but the
    // daemon's 15s heartbeat cannot, so the read is timed out rather than left
    // infinite. Left infinite, a stream an idle tunnel had dropped without a
    // close was never noticed: no `done` frame ever reached the live turn, so
    // "Sending request to provider…" stayed on screen ticking and every later
    // send was refused. A timeout is an ordinary stream failure — the state
    // goes OFFLINE, the backoff below redials from the last applied sequence,
    // and the reconnect recovers the turn the daemon may still be running.
    private val streamHttp = http.newBuilder()
        .readTimeout(STREAM_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var session = "default"
    @Volatile private var runSession = ""
    @Volatile private var runId: String? = null
    @Volatile private var lastSeq = 0L
    @Volatile private var terminal = false
    private var streamJob: Job? = null
    private var probeJob: Job? = null
    @Volatile private var currentCall: Call? = null

    override fun open(sessionId: String, fromSeq: Long) {
        session = sessionId
        follow(sessionId)
    }

    /** Keeps probing while the engine is unreachable, then resumes this chat. */
    private fun follow(sessionId: String) {
        probeJob?.cancel()
        probeJob = scope.launch {
            // A live connection serves every chat. Probing again on each switch
            // only flashed CONNECTING on every tap for a link that was already
            // up; the new chat's run is attached below, and a link that really
            // dropped is found by the next call, whose failure re-probes.
            if (_state.value != ConnState.ONLINE) {
                var wait = 0L
                while (scope.isActive) {
                    if (wait > 0) delay(wait)
                    if (probe()) break
                    wait = 5_000L
                }
                if (!scope.isActive || _state.value != ConnState.ONLINE) return@launch
            }
            if (session != sessionId) return@launch
            val current = runId
            if (current != null && runSession == sessionId) {
                // This process already owns the stream; reconnecting it
                // from zero here would replay deltas into a live turn
                // that already has them.
                if (streamJob?.isActive != true) {
                    terminal = false
                    startStream(current)
                }
            } else {
                resumeRemembered(sessionId)
                pump()
            }
        }
    }

    override fun switchSession(sessionId: String) {
        session = sessionId
        // A stream belongs to one chat. Switching views must not turn its
        // frames into deltas for the chat that just opened.
        currentCall?.cancel()
        streamJob?.cancel()
        streamJob = null
        runId = null
        runSession = ""
        lastSeq = 0
        terminal = false
    }

    override fun cancel(sessionId: String): Boolean {
        synchronized(queueLock) {
            queue.removeAll { it.sessionId == sessionId }
        }
        // Stop owns only the chat on screen. A stale stop from the chat just
        // left must not cancel the turn that replaced it.
        if (sessionId != runSession) return false
        val id = runId ?: return false
        scope.launch {
            val cfg = config() ?: return@launch
            runCatching {
                val req = Request.Builder()
                    .url("${cfg.base}/v1/runs/$id/cancel")
                    .header("Authorization", "Bearer ${cfg.token}")
                    .header("Content-Type", "application/json")
                    .post("{}".toRequestBody(JSON))
                    .build()
                http.newCall(req).execute().close()
            }.onFailure { Log.w(TAG, "cancel: ${it.message}") }
        }
        return true
    }

    override fun close() {
        // Do not cancel the server run: the app is a viewer. Only stop the
        // local HTTP call so a dead interface cannot hold a coroutine forever.
        currentCall?.cancel()
        streamJob?.cancel()
        probeJob?.cancel()
        streamJob = null
        _state.value = ConnState.OFFLINE
    }

    override fun reconnect() {
        probeJob?.cancel()
        probeJob = scope.launch {
            _state.value = ConnState.CONNECTING
            var wait = 0L
            while (scope.isActive) {
                if (wait > 0) delay(wait)
                if (probe()) break
                wait = 5_000L
            }
            if (!scope.isActive || _state.value != ConnState.ONLINE) return@launch
            val id = runId
            if (id != null && streamJob?.isActive != true) {
                terminal = false
                startStream(id)
            } else if (id == null) {
                resumeRemembered(session)
            }
        }
    }

    override fun retry(sessionId: String, clientMsgId: String): Boolean {
        start(sessionId, "", clientMsgId, retry = true, attachments = emptyList())
        return true
    }

    override fun send(
        sessionId: String,
        text: String,
        clientMsgId: String,
        attachments: List<AttachmentRef>,
    ): Boolean {
        start(sessionId, text, clientMsgId, retry = false, attachments = attachments)
        return true
    }

    // ---- run creation ----

    /**
     * One start at a time. The daemon allows one live turn per chat, so a
     * second question typed while the first is running has to wait here
     * instead of racing a POST and being rejected.
     */
    private val queue = mutableListOf<PendingStart>()
    private val queueLock = Any()
    @Volatile private var starting = false

    private data class PendingStart(
        val sessionId: String,
        val text: String,
        val clientMsgId: String,
        val retry: Boolean,
        val attachments: List<AttachmentRef>,
    )

    private fun start(
        sessionId: String,
        text: String,
        clientMsgId: String,
        retry: Boolean,
        attachments: List<AttachmentRef>,
    ) {
        synchronized(queueLock) {
            if (queue.none { it.clientMsgId == clientMsgId }) {
                queue.add(PendingStart(sessionId, text, clientMsgId, retry, attachments))
            }
        }
        pump()
    }

    private fun pump() {
        val req: PendingStart
        synchronized(queueLock) {
            if (starting || runId != null || queue.isEmpty()) return
            req = queue.removeAt(0)
            starting = true
        }
        scope.launch {
            var retry = false
            try {
                retry = !post(req)
            } finally {
                starting = false
            }
            if (runId == null) {
                if (retry) {
                    // The request is back at the front of the queue. Let the
                    // probe decide when the tunnel is usable; it pumps after a
                    // successful probe.
                    follow(req.sessionId)
                    return@launch
                }
                delay(700)
                pump()
            }
        }
    }

    /** Returns true when the run was accepted, false when it is queued again. */
    private suspend fun post(req: PendingStart): Boolean {
        val cfg = config()
        if (cfg == null) {
            requeue(req)
            _state.value = ConnState.OFFLINE
            return false
        }
        val body = JSONObject()
            .put("session_id", req.sessionId)
            .put("message", req.text)
            .put("client_run_id", req.clientMsgId)
            .put("retry", req.retry)
        if (req.attachments.isNotEmpty()) {
            val array = org.json.JSONArray()
            for (attachment in req.attachments) {
                array.put(
                    JSONObject()
                        .put("path", attachment.path)
                        .put("name", attachment.name)
                        .put("mime", attachment.mime)
                )
            }
            body.put("attachments", array)
        }

        val request = Request.Builder()
            .url("${cfg.base}/v1/runs")
            .header("Authorization", "Bearer ${cfg.token}")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    _state.value = if (response.code >= 500) ConnState.OFFLINE else ConnState.ONLINE
                    val message = runCatching {
                        JSONObject(raw).optJSONObject("error")?.optString("message")
                    }.getOrNull().orEmpty().ifBlank { "run failed: HTTP ${response.code}" }
                    if (response.code >= 500) {
                        requeue(req)
                        return@use false
                    }
                    emitError(req.sessionId, message)
                    return@use true
                }
                val env = runCatching { JSONObject(raw) }.getOrNull()
                val run = env?.optJSONObject("run")
                val id = run?.optString("id").orEmpty()
                if (id.isBlank()) {
                    emitError(req.sessionId, "the daemon did not return a run id")
                    return@use true
                }
                _state.value = ConnState.ONLINE
                // The create response is the acknowledgement: the daemon has
                // stored the question and owns the turn now.
                if (!req.retry) {
                    _frames.tryEmit(
                        AiOutput(
                            sessionId = req.sessionId,
                            kind = "ack",
                            clientMsgId = req.clientMsgId,
                        )
                    )
                }
                remember(req.sessionId, id)
                runId = id
                runSession = req.sessionId
                lastSeq = 0
                terminal = false
                startStream(id)
                true
            }
        } catch (e: CancellationException) {
            requeue(req)
            throw e
        } catch (e: Exception) {
            // Offline is a normal state: keep the work queued and let the
            // probe send it when the tunnel is back.
            _state.value = ConnState.OFFLINE
            Log.w(TAG, "start: ${e.message}")
            requeue(req)
            false
        }
    }

    private fun requeue(req: PendingStart) {
        synchronized(queueLock) {
            queue.add(0, req)
        }
    }

    private fun startStream(id: String) {
        currentCall?.cancel()
        streamJob?.cancel()
        streamJob = scope.launch { stream(id) }
    }

    // ---- events ----

    private suspend fun stream(id: String) {
        var backoff = 1_000L
        while (scope.isActive && !terminal) {
            val cfg = config() ?: run {
                _state.value = ConnState.OFFLINE
                return
            }
            val before = lastSeq
            val request = Request.Builder()
                .url("${cfg.base}/v1/runs/$id/events?after=$lastSeq&poll=1")
                .header("Authorization", "Bearer ${cfg.token}")
                .header("Accept", "text/event-stream")
                .get()
                .build()
            try {
                val call = streamHttp.newCall(request)
                currentCall = call
                call.execute().use { response ->
                    currentCall = null
                    if (!response.isSuccessful) {
                        if (response.code == 404) {
                            emitError(frameSession(), "this run is no longer available")
                            clearRun()
                            return
                        }
                        _state.value = ConnState.OFFLINE
                    } else {
                        _state.value = ConnState.ONLINE
                        backoff = 1_000L
                        val source = response.body?.source() ?: return
                        var event = ""
                        val data = StringBuilder()
                        while (scope.isActive && !terminal) {
                            val line = source.readUtf8Line() ?: break
                            if (line.isEmpty()) {
                                if (data.isNotEmpty()) {
                                    handle(id, event, data.toString())
                                    event = ""
                                    data.setLength(0)
                                }
                                continue
                            }
                            when {
                                line.startsWith(":") -> Unit
                                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                                line.startsWith("data:") -> data.append(line.removePrefix("data:").trimStart())
                                // id: duplicates the seq inside the event JSON.
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ConnState.OFFLINE
                Log.w(TAG, "stream: ${e.message}")
            }
            if (terminal) return
            if (lastSeq > before) {
                delay(POLL_HOT_MS)
            } else if (_state.value == ConnState.ONLINE) {
                delay(POLL_IDLE_MS)
            } else {
                // A failed poll backs off like the old stream did, so a tunnel
                // that is down is not dialled hundreds of times a minute.
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(15_000L)
            }
        }
    }

    private fun handle(run: String, event: String, raw: String) {
        // A stream belongs to one chat. A reader that was cancelled by a switch
        // can still deliver a line it had already read; labelling that line
        // with the newly selected chat (or updating its cursor) is how an old
        // answer appeared in the new thread.
        val ownedRun = runId ?: return
        if (run != ownedRun || runSession.isBlank() || runSession != session) return
        val env = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val seq = env.optLong("seq")
        if (seq > lastSeq) lastSeq = seq
        val kind = env.optString("kind").ifBlank { event }
        val data = env.opt("data")
        if (kind == "user") return
        val frame = RunFrames.map(kind, data, runSession) ?: return
        emit(frame)
        if (kind == "status" && RunFrames.terminal((data as? JSONObject)?.optString("status").orEmpty())) {
            clearRun()
        }
    }

    private fun frameSession(): String = runSession.ifBlank { session }

    // ---- cursor / probing ----

    private suspend fun probe(): Boolean {
        _state.value = ConnState.CONNECTING
        val cfg = config() ?: run {
            _state.value = ConnState.OFFLINE
            return false
        }
        val request = Request.Builder()
            .url("${cfg.base}/v1/sessions")
            .header("Authorization", "Bearer ${cfg.token}")
            .header("Accept", "application/json")
            .get()
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    _state.value = ConnState.ONLINE
                    true
                } else {
                    _state.value = if (response.code >= 500) ConnState.OFFLINE else ConnState.ONLINE
                    response.code < 500
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = ConnState.OFFLINE
            Log.w(TAG, "probe: ${e.message}")
            false
        }
    }

    private suspend fun resumeRemembered(sessionId: String) {
        val id = cursor.getString(keyRun(sessionId), null) ?: return
        val cfg = config() ?: return
        val request = Request.Builder()
            .url("${cfg.base}/v1/runs/$id")
            .header("Authorization", "Bearer ${cfg.token}")
            .header("Accept", "application/json")
            .get()
            .build()
        val status = try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use ""
                val raw = response.body?.string().orEmpty()
                runCatching { JSONObject(raw).optJSONObject("run")?.optString("status") }.getOrNull().orEmpty()
            }
        } catch (e: Exception) {
            return
        }
        if (status == "running") {
            runId = id
            runSession = sessionId
            lastSeq = 0
            terminal = false
            _state.value = ConnState.ONLINE
            startStream(id)
        } else {
            forget(sessionId)
        }
    }

    private fun remember(sessionId: String, id: String) {
        cursor.edit().putString(keyRun(sessionId), id).apply()
    }

    private fun clearRun() {
        terminal = true
        runId = null
        forget(runSession.ifBlank { session })
        // The daemon removes a finished run from its active set just after the
        // terminal event. A short pause avoids racing that cleanup with the
        // next queued question.
        scope.launch {
            delay(500)
            pump()
        }
    }

    private fun forget(sessionId: String) {
        cursor.edit().remove(keyRun(sessionId)).apply()
    }

    private fun keyRun(sessionId: String): String = "run:$sessionId"

    private fun emit(frame: AiOutput?) {
        if (frame != null) _frames.tryEmit(frame)
    }

    private fun emitError(sessionId: String, message: String) {
        _frames.tryEmit(
            AiOutput(
                sessionId = sessionId,
                kind = "error",
                text = message,
                agent = "system",
            )
        )
    }

    private suspend fun config(): Config? {
        val current = settings.current()
        val base = current.daemonUrl.trim().trimEnd('/')
        if (base.isEmpty() || current.token.isBlank()) return null
        if (!base.startsWith("http://") && !base.startsWith("https://")) return null
        return Config(base, current.token)
    }

    private data class Config(val base: String, val token: String)

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
