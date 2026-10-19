package com.tulipskun.aixodia.data.remote

import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.tulipskun.aixodia.R
import com.tulipskun.aixodia.SettingsStore
import com.tulipskun.aixodia.term.TerminalModes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "TerminalSocket"

/** The gap between open attempts. Discovery polls every 30s, so this covers it. */
private const val RETRY_MS = 1_500L

/** Where the terminal window stands, for the header line. */
enum class TermState { CLOSED, CONNECTING, OPEN, ENDED }

/**
 * What the engine says on the text channel. These are control frames only —
 * everything the shell prints travels as raw bytes (see [TerminalSocket.output]).
 */
sealed interface TermEvent {
    /** The shell is up: what it is and where it starts. */
    data class Ready(val shell: String, val cwd: String, val cols: Int, val rows: Int, val id: String = "") : TermEvent

    /** The shell ended on its own, with this exit code. The stream closes after. */
    data class Exit(val code: Int) : TermEvent

    /** Something failed after the handshake; the stream closes after this too. */
    data class Failed(val message: String) : TermEvent
}

/**
 * One terminal, carried as a stream inside the app's single relay socket.
 *
 * It used to be its own WebSocket to `/terminal`, which needed a second
 * handshake and a second reachable path. The relay has neither: one socket
 * carries chat, the API and every terminal. A terminal is opened with a control
 * frame, addressed by an id, and closed when the window goes.
 *
 * The shell itself still lives on the engine, keyed by the persisted session
 * id, so a killed-and-reopened app comes back to the same shell and its ring
 * buffer.
 */
class TerminalSocket(
    private val settings: SettingsStore,
    private val relay: RelayClient? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resizeAdapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        .adapter(ResizeFrame::class.java)

    private val _state = MutableStateFlow(TermState.CLOSED)
    val state: StateFlow<TermState> = _state

    /** Known session ids on the engine, in creation order. */
    private val _sessions = MutableStateFlow<List<String>>(emptyList())
    val sessions: StateFlow<List<String>> = _sessions

    /** The id the next dial will try to reattach to (empty = ask for a new one). */
    private val _activeSessionId = MutableStateFlow("")
    val activeSessionId: StateFlow<String> = _activeSessionId

    /** A string resource id for why the terminal is not up; 0 when nothing is wrong. */
    private val _error = MutableStateFlow(0)
    val error: StateFlow<Int> = _error

    /** Detailed, English, human-readable status for the panel — no logcat needed. */
    private val _detail = MutableStateFlow("")
    val detail: StateFlow<String> = _detail

    // A channel, not a StateFlow: terminal output is a stream, and a dropped
    // frame would leave the screen showing something the shell never printed.
    // Nothing is consumed until the view can render it, so a shell that answers
    // before the panel is up is not lost.
    private val _output = Channel<ByteArray>(Channel.UNLIMITED)
    val output: Flow<ByteArray> = _output.receiveAsFlow()

    /**
     * The last modes seen for a session. The engine replays only the tail of a
     * session's output, so a window that reattaches to a running program gets
     * pixels but not the mode sequences that said "this is a full-screen app
     * with the mouse on". The panel puts these back before the replay is drawn.
     */
    private val sessionModes = java.util.concurrent.ConcurrentHashMap<String, TerminalModes>()

    fun rememberedModes(id: String): TerminalModes? =
        if (id.isBlank()) null else sessionModes[id]

    fun rememberModes(id: String, modes: TerminalModes) {
        if (id.isNotBlank()) sessionModes[id] = modes
    }

    private val _events = Channel<TermEvent>(Channel.UNLIMITED)
    val events: Flow<TermEvent> = _events.receiveAsFlow()

    /** The stream id this socket opened; addresses every byte of the stream. */
    @Volatile
    private var sid: Int? = null

    // The one dialing coroutine. open() and close() cancel it before starting a
    // new one, so a stream that is being replaced can never race its successor
    // back to life — the previous design let an old dial's late callbacks run
    // against the new session.
    private var dialJob: Job? = null

    // Whether the window wants a terminal. open() sets it, close() clears it.
    // Unlike [isLive] it stays true while dial() is still waiting for the relay
    // address, which is the state a fresh sign-in starts in.
    @Volatile
    private var wanted = false

    // Touched from the socket's own callback thread as well as the UI's, so the
    // two flags that decide what a close means have to be visible across threads.
    @Volatile
    private var closing = false

    /** The last size the page asked for, sent when the stream opens. */
    @Volatile
    private var pendingSize: Pair<Int, Int>? = null

    /** Opens the stream, if it is not already dialing or up. */
    fun open() {
        wanted = true
        scope.launch {
            _activeSessionId.value = settings.currentTerminalSessionId()
            _sessions.value = settings.currentTerminalSessionIds()
        }
        // A stream left over from a previous session that died silently (app
        // backgrounded, Doze, dropped Wi-Fi) would make this return without
        // reconnecting, leaving the terminal stuck on a dead connection. Only
        // skip dialing when the stream is genuinely open.
        if (sid != null && _state.value == TermState.OPEN) return
        dialJob?.cancel()
        sid?.let { closeStream(it) }
        sid = null
        closing = false
        _error.value = 0
        // Events from the session that just ended are still sitting in the
        // unbounded channel if nobody was collecting when they arrived (the
        // panel was already disposed). Draining them here is what keeps a
        // reopened window from instantly showing the previous run's exit.
        drainEvents()
        drainOutput()
        _state.value = TermState.CONNECTING
        dialJob = scope.launch { dial() }
    }

    /**
     * Re-dials now, without waiting for the current link to fail. Used when
     * discovery has just moved the engine address, or when the app returns to
     * the foreground and the stream may have been dropped while away.
     */
    fun reconnect() {
        if (!wanted) return
        reconnectLocked()
    }

    private fun reconnectLocked() {
        dialJob?.cancel()
        sid?.let { closeStream(it) }
        sid = null
        closing = false
        _error.value = 0
        _state.value = TermState.CONNECTING
        dialJob = scope.launch { dial() }
    }

    private fun drainEvents() {
        while (_events.tryReceive().isSuccess) { /* drop stale events */ }
    }

    private fun drainOutput() {
        while (_output.tryReceive().isSuccess) { /* drop stale bytes */ }
    }

    /**
     * Ends the current shell and opens a new one. Used after an exit or an
     * error, where the stream is already gone and only a fresh one can give
     * back a prompt.
     */
    fun restart() {
        close()
        open()
    }

    /** Switches to an already-known session id and re-dials. Used by the tab row. */
    fun switchSession(id: String) {
        if (id.isBlank()) return
        scope.launch {
            settings.saveTerminalSessionId(id)
            _activeSessionId.value = id
            close()
            open()
        }
    }

    /**
     * Kills the current shell and forgets its id, then leaves the panel closed.
     * Used by the "close session" button: it drops the tab and hangs up, and
     * nothing dials again until the user asks for a shell (the header's
     * open-new button).
     */
    fun closeSession() {
        val id = _activeSessionId.value
        scope.launch {
            if (id.isNotBlank()) {
                sessionModes.remove(id)
                val ids = settings.currentTerminalSessionIds().toMutableList()
                ids.remove(id)
                settings.saveTerminalSessionIds(ids)
                settings.clearTerminalSessionId()
            }
            _sessions.value = settings.currentTerminalSessionIds()
            _activeSessionId.value = ""
        }
        close()
        // Show it as finished, not as the brief CLOSED of a window that is about
        // to dial: the header then reads "Connection closed" and offers the
        // open-new button.
        _state.value = TermState.ENDED
    }

    /** Hangs up. The engine keeps the shell for a reattach, as it always has. */
    fun close() {
        wanted = false
        closing = true
        dialJob?.cancel()
        dialJob = null
        sid?.let { closeStream(it) }
        sid = null
        _state.value = TermState.CLOSED
    }

    /**
     * Starts a brand-new shell on the engine, dropping any reattach id so the
     * old (possibly still running) shell is left behind. Used by "open new shell".
     */
    fun newSession() {
        scope.launch {
            settings.clearTerminalSessionId()
            close()
            open()
        }
    }

    /**
     * True while a dial is in flight or a shell is up — the terminal is doing
     * something and does not need to be poked. Used when the app returns to the
     * foreground, where a link that has quietly died should be redialed.
     */
    fun isLive(): Boolean =
        wanted && (_state.value == TermState.OPEN || _state.value == TermState.CONNECTING)

    /**
     * Keystrokes to the shell, exactly as typed: control characters are bytes,
     * not commands (Ctrl-C is 0x03). False when the stream is not up.
     */
    fun send(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val id = sid ?: return false
        return relay?.termSend(id, bytes) == true
    }

    /** Tells the shell its window changed, so full-screen programs redraw. */
    fun resize(cols: Int, rows: Int): Boolean {
        if (cols <= 0 || rows <= 0) return false
        // The view reports its size as soon as it is laid out, which is usually
        // before the stream is up. Keeping it means the shell is never left at
        // the default 80x24 while the screen draws something else.
        pendingSize = cols to rows
        val id = sid ?: return false
        return relay?.termJSON(id, resizeFrame(cols, rows)) == true
    }

    private fun resizeFrame(cols: Int, rows: Int): String =
        resizeAdapter.toJson(ResizeFrame(type = "resize", cols = cols, rows = rows))

    private fun closeStream(id: Int) {
        relay?.closeTerminal(id)
    }

    private suspend fun dial() {
        var backoff = RETRY_MS
        while (!closing) {
            val c = try {
                settings.current()
            } catch (t: Throwable) {
                // A read that fails is not "there is no daemon": DataStore can
                // hiccup. Retry rather than showing a dead end nobody can clear.
                Log.w(TAG, "settings unreadable", t)
                _detail.value = "settings.current() threw: ${t.message}"
                delay(RETRY_MS)
                continue
            }
            if (c.token.isBlank()) {
                // Nothing to connect with: the sign-in gate has not been passed.
                _error.value = R.string.terminal_no_address
                _state.value = TermState.CLOSED
                _detail.value = "token blank in settings"
                return
            }
            if (c.wsUrl.isBlank()) {
                // The token is set but the address is not known yet: the app
                // opened before discovery read D1. Keep waiting for as long as
                // the window is open; dial() re-reads settings every pass.
                _detail.value = "wsUrl empty; waiting for discovery"
                delay(RETRY_MS)
                continue
            }
            val r = relay
            if (r == null) {
                _error.value = R.string.terminal_no_address
                _state.value = TermState.CLOSED
                _detail.value = "no relay client"
                return
            }
            r.connect()
            _detail.value = "waiting for the relay"
            val online = withTimeoutOrNull(15_000) {
                r.state.first { it == ConnState.ONLINE }
            } != null
            if (!online) {
                _detail.value = "relay offline; retrying"
                delay(backoff)
                backoff = minOf(backoff * 2, 10_000L)
                continue
            }

            // Reattach to a surviving shell when we have its id; otherwise the
            // engine hands back a fresh one in its ready frame.
            val wantedId = settings.currentTerminalSessionId()
            val (cols, rows) = pendingSize ?: (80 to 24)
            _detail.value = "opening terminal stream"
            // Completed once the stream goes away: true when the shell ended on
            // its own (exit or error frame), false when the link dropped. That
            // is the difference between "show the result" and "dial again".
            val ended = CompletableDeferred<Boolean>()
            var mySid = -2
            val stream = object : TermStream {
                // A stream that is no longer the current one must not touch
                // shared state; its close callback can arrive after the next
                // dial has already begun.
                private fun stale(): Boolean = (sid ?: -1) != mySid

                override fun onText(json: String) {
                    if (stale()) return
                    when (val ev = parseEvent(json)) {
                        is TermEvent.Exit -> {
                            // The shell finished: an outcome to show, not
                            // something to retry, so dial() stops here.
                            _state.value = TermState.ENDED
                            _events.trySend(ev)
                            ended.complete(true)
                        }

                        is TermEvent.Failed -> {
                            _state.value = TermState.ENDED
                            _events.trySend(ev)
                            ended.complete(true)
                        }

                        is TermEvent.Ready -> {
                            if (ev.id.isNotBlank()) {
                                scope.launch {
                                    settings.saveTerminalSessionId(ev.id)
                                    val ids = settings.currentTerminalSessionIds().toMutableList()
                                    if (ev.id !in ids) ids.add(ev.id)
                                    settings.saveTerminalSessionIds(ids)
                                    _sessions.value = ids
                                    _activeSessionId.value = ev.id
                                }
                            }
                            _state.value = TermState.OPEN
                            _error.value = 0
                            _detail.value = "shell ${ev.shell.ifBlank { "?" }} at ${ev.cwd}"
                            _events.trySend(ev)
                        }

                        // An unreadable control frame is not worth ending a
                        // working shell over; the bytes keep flowing either way.
                        null -> Log.w(TAG, "unreadable control frame: ${json.take(120)}")
                    }
                }

                override fun onBytes(bytes: ByteArray) {
                    if (stale()) return
                    _output.trySend(bytes)
                }

                override fun onClosed() {
                    ended.complete(false)
                    if (stale()) return
                    sid = null
                }
            }
            val opened = r.openTerminal(wantedId, cols, rows, stream)
            if (opened == null) {
                _detail.value = "could not open a terminal stream"
                delay(backoff)
                backoff = minOf(backoff * 2, 10_000L)
                continue
            }
            mySid = opened
            sid = opened
            backoff = RETRY_MS
            _state.value = TermState.OPEN
            _detail.value = "terminal stream $opened"

            val shellEnded = ended.await()
            if (sid == opened) sid = null
            if (closing) return
            if (shellEnded) return
            // The link dropped under a window that is still open — the app was
            // backgrounded, the engine moved, Wi-Fi changed. Reconnect instead of
            // leaving the panel on a dead connection.
            _state.value = TermState.CONNECTING
            _error.value = 0
            delay(RETRY_MS)
        }
    }

    companion object {
        /**
         * The direct-terminal address of the pre-relay engine. The app no longer
         * dials it, but the mapping is still what a local engine run publishes,
         * and the panel's status line shows it.
         */
        fun terminalUrlFor(wsUrl: String): String {
            val clean = wsUrl.trim().trimEnd('/')
            val at = clean.indexOf("://")
            if (at <= 0) return ""
            val scheme = when (clean.substring(0, at).lowercase()) {
                "http", "ws" -> "ws"
                "https", "wss" -> "wss"
                else -> return ""
            }
            val host = clean.substring(at + 3).substringBefore('/')
            if (host.isBlank()) return ""
            return "$scheme://$host/terminal"
        }
    }
}

private val controlMoshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
private val controlAdapter = controlMoshi.adapter(ControlFrame::class.java)

/** Parses one control frame; null when it is not one we know. */
internal fun parseEvent(text: String): TermEvent? {
    val f = try {
        controlAdapter.fromJson(text)
    } catch (_: Exception) {
        null
    } ?: return null
    return when (f.type) {
        "ready" -> TermEvent.Ready(f.shell.orEmpty(), f.cwd.orEmpty(), f.cols ?: 0, f.rows ?: 0, f.id.orEmpty())
        "exit" -> TermEvent.Exit(f.code ?: 0)
        "error" -> TermEvent.Failed(f.error.orEmpty())
        else -> null
    }
}

/**
 * The engine's text frames, §3 of the engine spec. Every field is nullable with
 * the key possibly absent, so one reader covers all three frame types and an
 * unknown field is simply ignored.
 */
internal data class ControlFrame(
    val type: String?,
    val shell: String?,
    val cwd: String?,
    val cols: Int?,
    val rows: Int?,
    val code: Int?,
    val error: String?,
    val id: String?,
)

/** `{"type":"resize","cols":100,"rows":30}` — both sizes are always sent. */
internal data class ResizeFrame(
    val type: String,
    val cols: Int,
    val rows: Int,
)
