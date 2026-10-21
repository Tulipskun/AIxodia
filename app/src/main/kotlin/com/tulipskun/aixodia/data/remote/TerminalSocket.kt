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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

private const val TAG = "TerminalSocket"

/** The gap between open attempts. Discovery polls every 30s, so this covers it. */
private const val RETRY_MS = 1_500L

/**
 * How long the app may spend in the background before a still-"open" terminal
 * stream is treated as suspect. Android can freeze or drop the socket while
 * Doze keeps the process alive; the old listener then stays OPEN forever and
 * the screen never gets a replay. A quick app switch is left alone so a healthy
 * screen is not reset for a glance at a notification.
 */
private const val REATTACH_AFTER_BACKGROUND_MS = 30_000L

/** Whether returning to the foreground should rebuild an existing stream. */
internal fun shouldReattachAfterBackground(state: TermState, wanted: Boolean, awayMs: Long): Boolean =
    wanted && state == TermState.OPEN && awayMs >= REATTACH_AFTER_BACKGROUND_MS

/**
 * How long a freshly opened stream may stay silent before it is treated as
 * dead. The engine answers an open with ready (or error) at once; no answer
 * means the frame or the stream id was stale, and dialing again with a new id
 * is the only way out.
 */
private const val READY_TIMEOUT = 10_000L

/**
 * How often a dialing terminal re-reads the daemon address itself. Discovery
 * polls every 30s, but an engine redeploy replaces the quick-tunnel URL and the
 * app may still be holding the old one when the window opens. Waiting for the
 * next discovery tick made the terminal sit on "connecting" until an unrelated
 * chat send woke it; opening a terminal now checks the row before dialing.
 */
private const val DAEMON_RECHECK_MS = 30_000L

/** Where the terminal window stands, for the header line. */
enum class TermState { CLOSED, CONNECTING, OPEN, ENDED }

/**
 * What the engine says on the text channel. These are control frames only —
 * everything the shell prints travels as raw bytes (see [TerminalSocket.output]).
 */
sealed interface TermEvent {
    /** The shell is up: what it is and where it starts. */
    data class Ready(
        val shell: String,
        val cwd: String,
        val cols: Int,
        val rows: Int,
        val id: String = "",
        val pid: Int = 0,
    ) : TermEvent

    /** The shell ended on its own, with this exit code. The stream closes after. */
    data class Exit(val code: Int) : TermEvent

    /** Something failed after the handshake; the stream closes after this too. */
    data class Failed(val message: String) : TermEvent
}

/**
 * One terminal, carried as a stream inside the app's single engine socket.
 *
 * It used to be its own WebSocket to `/terminal`, which needed a second
 * handshake and a second reachable path. The one `/room` socket carries chat,
 * the API and every terminal instead. A terminal is opened with a control
 * frame, addressed by an id, and closed when the window goes.
 *
 * The shell itself still lives on the engine, keyed by the persisted session
 * id, so a killed-and-reopened app comes back to the same shell and its ring
 * buffer.
 */
class TerminalSocket(
    private val settings: SettingsStore,
    private val relay: RelayClient? = null,
    private val history: HistoryApi? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resizeAdapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        .adapter(ResizeFrame::class.java)

    // The initial dial and the periodic refresh both reconcile the tab list.
    // Serializing them keeps an older answer from overwriting a newer one.
    private val sessionSync = Mutex()

    private val _state = MutableStateFlow(TermState.CLOSED)
    val state: StateFlow<TermState> = _state

    /** Known session ids on the engine, in creation order. */
    private val _sessions = MutableStateFlow<List<String>>(emptyList())
    val sessions: StateFlow<List<String>> = _sessions

    /** The pid the engine named for each known session id. */
    private val _sessionPids = MutableStateFlow<Map<String, Int>>(emptyMap())
    val sessionPids: StateFlow<Map<String, Int>> = _sessionPids

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

    // The periodic tab refresh, so a slow list call cannot queue one per tick.
    private var refreshJob: Job? = null

    init {
        // The engine can drop and re-register while the phone's socket never
        // moves (a relay edge used to tell us with presence false/true). Every
        // stream id lives in that engine-side session, so at that moment all of
        // them are dead even though ours look open. Presence coming back is the
        // signal to reattach the shell on screen.
        scope.launch {
            var wasUp = relay?.enginePresent?.value ?: true
            relay?.enginePresent?.collect { up ->
                if (up && !wasUp && wanted) reconnectLocked()
                wasUp = up
            }
        }
    }

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

    /**
     * The id the next dial must use, overriding settings until the write lands.
     * Closing or switching a tab changes it synchronously: the DataStore edit is
     * asynchronous, and a dial that started in between would otherwise reattach
     * the shell that was just closed — or the one before the tab was tapped.
     * Null means "read settings".
     */
    @Volatile
    private var retarget: String? = null

    // Whether this open has already reconciled against /v1/terminals. It is
    // what stops the saved-state read below from overwriting an answer that
    // arrived first, when the list no longer has the saved shell.
    @Volatile
    private var sessionsSynced = false

    /** Opens the stream, if it is not already dialing or up. */
    fun open() {
        wanted = true
        sessionsSynced = false
        scope.launch {
            // The initial dialog and the first reconcile both touch the tab
            // list. Holding the same lock as syncSessions keeps whichever runs
            // second from painting stale saved ids over the engine's answer.
            sessionSync.withLock {
                if (sessionsSynced) return@withLock
                when (val target = retarget) {
                    null -> _activeSessionId.value = settings.currentTerminalSessionId()
                    "" -> _activeSessionId.value = ""
                    else -> _activeSessionId.value = target
                }
                _sessions.value = settings.currentTerminalSessionIds()
                _sessionPids.value = settings.currentTerminalSessionPids()
            }
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

    /**
     * Re-reads the engine's list of live shells while the window is open. The
     * initial dial already does this once; a shell can be killed from another
     * terminal, or another phone can open one, long after that. Without this
     * poll the tab row kept a dead pid and a tap reattached the same dead id.
     *
     * It is deliberately silent: a failed list (relay briefly offline, an old
     * engine without /v1/terminals) leaves the current tabs alone, exactly as
     * the dial's reconcile does.
     */
    fun refreshSessions() {
        val r = relay ?: return
        if (r.state.value != ConnState.ONLINE || refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            val lostActive = syncSessions(r, settings.currentTerminalSessionId())
            // The shell on screen disappeared between polls. A live stream
            // may not know yet (the engine dropped the pty but its reader has
            // not delivered the exit), so replace it with the newest surviving
            // tab, or a fresh shell when none are left.
            if (lostActive && wanted &&
                (_state.value == TermState.OPEN || _state.value == TermState.CONNECTING)
            ) {
                reconnectLocked()
            }
        }
    }

    /**
     * Called when the activity comes back. A stream that survived a long
     * absence can look OPEN while Android has already dropped every byte, so
     * rebuild it once; the engine replays RIS, the modes and the ring, which is
     * exactly the recovery a fresh attach gives. A short absence keeps the
     * current screen untouched.
     */
    fun onForeground(awayMs: Long) {
        // The list also changes while the app is away (an exit, a kill, a tab
        // opened from another phone), so reconcile it even for a short absence.
        refreshSessions()
        if (!shouldReattachAfterBackground(_state.value, wanted, awayMs)) return
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
        // Tapping the tab that is already attached must not close and re-open
        // the stream: that replays the screen (RIS + ring) and, in the status
        // line, looked like a counter going up on every tap.
        if (id == _activeSessionId.value && sid != null && _state.value == TermState.OPEN) return
        retarget = id
        _activeSessionId.value = id
        scope.launch {
            settings.saveTerminalSessionId(id)
            close()
            open()
        }
    }

    /**
     * Kills the current shell and forgets its id, then leaves the panel closed.
     * Used by the "close session" button: it drops the tab and ends the shell on
     * the engine, and nothing dials again until the user asks for a shell (the
     * header's open-new button).
     */
    fun closeSession() {
        val id = _activeSessionId.value
        val stream = sid
        retarget = ""
        _activeSessionId.value = ""
        // End the shell, not just the stream. Hanging up leaves the engine's
        // shell — and its slot — alive, so a handful of closes made every new
        // terminal fail with "too many terminals". The control frame goes out
        // before the close below on the same socket, so the engine sees it first.
        if (stream != null) relay?.termJSON(stream, """{"type":"close"}""")
        scope.launch {
            if (id.isNotBlank()) {
                sessionModes.remove(id)
                settings.removeTerminalSessionId(id)
            }
            _sessions.value = settings.currentTerminalSessionIds()
            _sessionPids.value = settings.currentTerminalSessionPids()
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
        // Empty means "ask the engine for a new id". Set before the DataStore
        // clear so a dial cannot reattach the old shell in between.
        retarget = ""
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

    /**
     * Aligns the tab list and pid labels with the engine's live shells. An
     * engine too old to serve /v1/terminals answers 404; the saved tabs are
     * then left alone, which is the old behaviour.
     *
     * A shell id is not a shell: after the engine restarts, the same saved id
     * would make acquireSession start a brand-new shell under the tab the user
     * thought was still running. Dropping ids the engine no longer reports is
     * what makes a reconnect land on the right shell — or on a new tab when
     * that shell really is gone.
     */
    private suspend fun syncSessions(r: RelayClient, savedId: String): Boolean =
        sessionSync.withLock {
            val reply = withTimeoutOrNull(5_000) { r.rpc("GET", "/v1/terminals") }
                ?: return@withLock false
            if (!reply.isSuccessful) return@withLock false
            val array = runCatching {
                JSONObject(reply.text()).optJSONArray("terminals")
            }.getOrNull() ?: return@withLock false

            val ids = mutableListOf<String>()
            val pids = mutableMapOf<String, Int>()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                // The engine already checks the pid; the alive flag keeps an
                // older engine's finished rows out of the tabs as well.
                if (id.isEmpty() || !o.optBoolean("alive", true)) continue
                ids += id
                val pid = o.optInt("pid", 0)
                if (pid > 0) pids[id] = pid
            }

            // The engine's answer is the whole truth: persist it in one edit so
            // a crash between the list and the map cannot leave them
            // disagreeing. Unchanged polls do not write DataStore.
            if (settings.currentTerminalSessionIds() != ids) {
                settings.saveTerminalSessionIds(ids)
            }
            if (settings.currentTerminalSessionPids() != pids) {
                settings.saveTerminalSessionPids(pids)
            }
            _sessions.value = ids
            _sessionPids.value = pids
            sessionsSynced = true

            val before = _activeSessionId.value
            // Only an id that was actually attached is "lost". A stale saved
            // id that no stream ever used just means this window has none.
            var lost = before.isNotBlank() && before !in ids

            val explicit = retarget
            if (explicit == null) {
                val active = before.ifBlank { savedId }
                _activeSessionId.value = when {
                    active.isNotBlank() && active in ids -> active
                    ids.isNotEmpty() -> ids.last()
                    else -> ""
                }
            } else if (explicit.isNotBlank() && explicit !in ids) {
                // The tab being switched to is gone: ask for a new shell rather
                // than spawning one behind the old label.
                retarget = ""
                _activeSessionId.value = ""
            } else if (explicit.isEmpty()) {
                // A brand-new shell is being requested, not a lost one.
                lost = false
            }
            lost
        }

    /**
     * Moves the stored address to the live daemon before the terminal dials.
     *
     * Discovery owns this normally, but its next tick can be up to 30s away
     * (longer after a failed read). During that gap a terminal opened right
     * after an engine self-update keeps dialing the dead quick-tunnel host and
     * looks disconnected. Returns true when the address changed, so the relay
     * socket is redialed with the new one too.
     */
    private suspend fun refreshDaemonAddress(): Boolean {
        val h = history ?: return false
        val current = try {
            settings.current()
        } catch (t: Throwable) {
            return false
        }
        if (current.token.isBlank()) return false
        val node = try {
            withTimeoutOrNull(5_000) { h.node() }
        } catch (t: Throwable) {
            Log.w(TAG, "daemon address refresh failed", t)
            null
        } ?: return false
        val url = node.tunnelUrl.trim().trimEnd('/')
        if (!node.online || url.isBlank()) return false
        if (url == current.daemonUrl.trim().trimEnd('/')) return false
        return try {
            settings.saveDaemon(url)
            Log.i(TAG, "daemon moved to $url")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "could not save the new daemon address", t)
            false
        }
    }

    private suspend fun dial() {
        var backoff = RETRY_MS
        var lastAddressCheck = 0L
        while (!closing) {
            val now = System.currentTimeMillis()
            if (history != null && now - lastAddressCheck >= DAEMON_RECHECK_MS) {
                lastAddressCheck = now
                if (refreshDaemonAddress()) relay?.reconnect()
            }
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

            // Reconcile the tabs with the shells the engine actually holds. A
            // saved id that survived an engine restart would otherwise be
            // reattached into a brand-new shell under an old tab, and the tab
            // would keep claiming to be the shell the user left. The list also
            // carries each shell's pid, so the labels are right before the
            // ready frame arrives.
            syncSessions(r, settings.currentTerminalSessionId())

            // Reattach to a surviving shell when we have its id; otherwise the
            // engine hands back a fresh one in its ready frame. syncSessions
            // has already put the saved id (or the newest live one) in
            // activeSessionId when it still exists on the engine.
            val wantedId = retarget ?: _activeSessionId.value
            // Zero when the view has not been laid out yet: the engine then
            // keeps the shell's current size instead of forcing 80x24 onto a
            // running program. The view sends the real size as soon as it is
            // measured, which resizes the pty and repaints a full-screen app.
            val (cols, rows) = pendingSize ?: (0 to 0)
            _detail.value = "opening terminal stream"
            // Completed once the stream goes away: true when the shell ended on
            // its own (exit or error frame), false when the link dropped. That
            // is the difference between "show the result" and "dial again".
            val ended = CompletableDeferred<Boolean>()
            // Completed by any control frame; dial() only waits for it to
            // tell "the engine answered" from "the open was swallowed".
            val readyFrame = CompletableDeferred<Unit>()
            var mySid = -2
            val stream = object : TermStream {
                // A stream that is no longer the current one must not touch
                // shared state; its close callback can arrive after the next
                // dial has already begun. Before openTerminal has returned,
                // sid is still null and mySid is still -2: events that arrive
                // in that window belong to this stream, not to a stale one.
                private fun stale(): Boolean {
                    val current = sid ?: return mySid != -2
                    return current != mySid
                }

                override fun onText(json: String) {
                    if (stale()) return
                    when (val ev = parseEvent(json)) {
                        is TermEvent.Exit -> {
                            // The shell finished: an outcome to show, not
                            // something to retry, so dial() stops here.
                            _state.value = TermState.ENDED
                            _events.trySend(ev)
                            readyFrame.complete(Unit)
                            ended.complete(true)
                        }

                        is TermEvent.Failed -> {
                            _state.value = TermState.ENDED
                            _events.trySend(ev)
                            readyFrame.complete(Unit)
                            ended.complete(true)
                        }

                        is TermEvent.Ready -> {
                            readyFrame.complete(Unit)
                            if (ev.id.isNotBlank()) {
                                // Synchronously, so a retry inside dial() and
                                // the tabs see the id before the DataStore
                                // write completes.
                                retarget = ev.id
                                _activeSessionId.value = ev.id
                                scope.launch {
                                    settings.saveTerminalSessionId(ev.id)
                                    settings.addTerminalSessionId(ev.id)
                                    if (ev.pid > 0) settings.saveTerminalSessionPid(ev.id, ev.pid)
                                    _sessions.value = settings.currentTerminalSessionIds()
                                    _sessionPids.value = settings.currentTerminalSessionPids()
                                }
                            } else if (ev.pid > 0) {
                                // A ready frame without an id cannot happen on
                                // the relay path; keep the pid for the active
                                // tab anyway rather than dropping the identity.
                                val id = _activeSessionId.value
                                if (id.isNotBlank()) {
                                    _sessionPids.value = _sessionPids.value + (id to ev.pid)
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
                    readyFrame.complete(Unit)
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
            // Still CONNECTING, not OPEN: the frame has been written but the
            // engine has not named the shell yet. Claiming OPEN here made the
            // header lose both "connecting" and the ready details for up to
            // READY_TIMEOUT, and showed a terminal as attached when the open
            // had in fact been swallowed.
            // No stream id here: it is an implementation detail that changes on
            // every attach, and the ready frame (above) already names the shell
            // and its directory. Printing it made a healthy terminal look like
            // it was counting attempts every time a tab was tapped.

            // A stream the engine never answers is dead even though the relay
            // socket is up: the open frame can be swallowed by a stale engine-
            // side stream id (the phone starts its ids over when its process
            // restarts). Dial again with a new id rather than waiting forever.
            if (withTimeoutOrNull(READY_TIMEOUT) { readyFrame.await() } == null) {
                if (sid == opened) {
                    closeStream(opened)
                    sid = null
                }
                if (closing) return
                _state.value = TermState.CONNECTING
                _error.value = 0
                _detail.value = "no answer from the engine; redialing"
                // Only replace the socket when the link itself is not up. While
                // the relay is ONLINE the engine answered the presence frame, so
                // a swallowed open is a stream id the engine no longer knows: a
                // new id on the same socket is enough, and tearing the socket
                // down would drop a chat turn that is streaming on it for a
                // terminal that never attached. When the state is not ONLINE
                // the socket may point at a tunnel edge that died with the old
                // engine, and redialing the stream alone would lose the open
                // frame forever.
                if (r.state.value != ConnState.ONLINE) r.reconnect()
                delay(RETRY_MS)
                continue
            }
            // The view can be laid out while openTerminal() is in flight. Its
            // resize is stored but cannot be sent yet (sid is not assigned),
            // so the open frame carried the size from before the race. Now that
            // the stream is up, send whatever the view last measured. Without
            // this the pty stays at 80x24 under a much taller view, and a
            // full-screen program paints only the top of the screen.
            sizeToResend(pendingSize, cols, rows)?.let { (latestCols, latestRows) ->
                r.termJSON(opened, resizeFrame(latestCols, latestRows))
            }
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

/**
 * The size the view measured after the open frame was already sent. A layout
 * can finish while openTerminal() is in flight; resize() stores that size but
 * cannot send it until the stream has an id.
 */
internal fun sizeToResend(
    pending: Pair<Int, Int>?,
    sentCols: Int,
    sentRows: Int,
): Pair<Int, Int>? = pending?.takeIf { (cols, rows) ->
    cols > 0 && rows > 0 && (cols != sentCols || rows != sentRows)
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
        "ready" -> TermEvent.Ready(f.shell.orEmpty(), f.cwd.orEmpty(), f.cols ?: 0, f.rows ?: 0, f.id.orEmpty(), f.pid ?: 0)
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
    val pid: Int?,
)

/** `{"type":"resize","cols":100,"rows":30}` — both sizes are always sent. */
internal data class ResizeFrame(
    val type: String,
    val cols: Int,
    val rows: Int,
)
