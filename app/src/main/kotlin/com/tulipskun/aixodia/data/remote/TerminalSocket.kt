package com.tulipskun.aixodia.data.remote

import android.util.Log
import com.tulipskun.aixodia.SettingsStore
import com.tulipskun.aixodia.term.TerminalModes
import java.net.URLEncoder
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

private const val TAG = "TerminalSocket"

/** The gap between connection attempts. Discovery polls every 30s, so this covers it. */
private const val RETRY_MS = 1_500L

/**
 * How long the app may spend in the background before a still-"open" terminal
 * stream is treated as suspect. Android can freeze or drop the socket while
 * Doze keeps the process alive; the old listener then stays OPEN forever and
 * the screen never gets a replay. A quick app switch is left alone so a healthy
 * screen is not reset for a glance at a notification.
 */
private const val REATTACH_AFTER_BACKGROUND_MS = 30_000L

/**
 * How long a freshly opened socket may stay silent before it is treated as
 * dead. The engine answers an open with ready (or error) at once; no answer
 * means the tunnel or the engine did not deliver the upgrade, and dialing again
 * with a fresh ticket is the only way out.
 */
private const val READY_TIMEOUT = 10_000L

/**
 * How often a dialing terminal re-reads the daemon address itself. Discovery
 * polls every 30s, but an engine redeploy replaces the quick-tunnel URL and the
 * app may still be holding the old one when the window opens.
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
 * One native terminal, carried by its own WebSocket to the engine's `/terminal`
 * endpoint.
 *
 * There is no WebView and no JavaScript terminal: [TerminalView] draws the
 * app's own emulator, so input and output are bytes all the way. The socket is
 * authenticated with a single-use, short-lived ticket fetched over the normal
 * authenticated HTTP path, so the long-lived API token never appears in a URL.
 *
 * The shell itself lives on the engine (in tmux when available), keyed by the
 * persisted session id, so a killed-and-reopened app comes back to the same
 * shell and its ring buffer.
 */
class TerminalSocket(
    private val settings: SettingsStore,
    private val history: HistoryApi,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        // A ping every twenty seconds notices a tunnel edge that stopped
        // forwarding, without tearing down an idle but healthy shell.
        .pingInterval(Duration.ofSeconds(20))
        .connectTimeout(Duration.ofSeconds(10))
        .readTimeout(Duration.ZERO)
        .build()

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
     * pixels but not always every mode sequence. The panel puts these back
     * before the replay is drawn.
     */
    private val sessionModes = ConcurrentHashMap<String, TerminalModes>()

    fun rememberedModes(id: String): TerminalModes? =
        if (id.isBlank()) null else sessionModes[id]

    fun rememberModes(id: String, modes: TerminalModes) {
        if (id.isNotBlank()) sessionModes[id] = modes
    }

    private val _events = Channel<TermEvent>(Channel.UNLIMITED)
    val events: Flow<TermEvent> = _events.receiveAsFlow()

    // The one dialing coroutine. open() and close() cancel it before starting a
    // new one, so a stream that is being replaced can never race its successor
    // back to life.
    private var dialJob: Job? = null

    // The periodic tab refresh, so a slow list call cannot queue one per tick.
    private var refreshJob: Job? = null

    // Whether the window wants a terminal. open() sets it, close() clears it.
    @Volatile
    private var wanted = false

    @Volatile
    private var closing = false

    /** Bumped on every new socket and on close; old callbacks compare it. */
    private val connection = AtomicInteger(0)

    @Volatile
    private var socket: WebSocket? = null

    /** The last size the view reported, sent when the stream opens. */
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
    @Synchronized
    fun open() {
        wanted = true
        closing = false
        // A stream left over from a previous session that died silently would
        // make this return without reconnecting, leaving a dead connection.
        if (socket != null && _state.value == TermState.OPEN) return
        sessionsSynced = false
        dialJob?.cancel()
        closeSocket()
        _error.value = 0
        // Events from the session that just ended are still sitting in the
        // unbounded channel if nobody was collecting when they arrived. Draining
        // them keeps a reopened window from instantly showing the old exit.
        drainEvents()
        drainOutput()
        _state.value = TermState.CONNECTING
        dialJob = scope.launch {
            // Load the saved tabs before the first dial: if the engine is
            // unreachable, dial() continues with this active id rather than
            // starting a new shell under an old tab.
            sessionSync.withLock {
                if (!sessionsSynced) {
                    when (val target = retarget) {
                        null -> _activeSessionId.value = settings.currentTerminalSessionId()
                        "" -> _activeSessionId.value = ""
                        else -> _activeSessionId.value = target
                    }
                    _sessions.value = settings.currentTerminalSessionIds()
                    _sessionPids.value = settings.currentTerminalSessionPids()
                }
            }
            dial()
        }
    }

    /**
     * Re-dials now, without waiting for the current link to fail. Used when the
     * app returns to the foreground after a long absence, or when discovery has
     * just moved the engine address.
     */
    fun reconnect() {
        if (!wanted) return
        reconnectLocked()
    }

    /**
     * Re-reads the engine's list of live shells while the window is open. A
     * shell can be killed from another terminal, or another phone can open one,
     * long after the initial dial.
     */
    fun refreshSessions() {
        if (!wanted) return
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            val lostActive = syncSessions()
            // The shell on screen disappeared between polls. A live socket may
            // not know yet (the engine dropped the pty but its reader has not
            // delivered the exit), so replace it with the newest surviving tab,
            // or a fresh shell when none are left.
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
     * rebuild it once; the engine replays RIS, the modes and the ring.
     *
     * A reattach deliberately does not also start a tab refresh: both paths
     * would race to replace the same socket, and the redial is the urgent one.
     * The panel's periodic refresh reconciles the tabs just after it is back.
     */
    fun onForeground(awayMs: Long) {
        if (shouldReattachAfterBackground(_state.value, wanted, awayMs)) {
            reconnectLocked()
            return
        }
        // A short absence changes nothing on the wire, but the list can still
        // have changed while the app was away (an exit, a kill, a tab opened
        // from another phone).
        refreshSessions()
    }

    @Synchronized
    private fun reconnectLocked() {
        if (!wanted) return
        dialJob?.cancel()
        closeSocket()
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
        // the stream: that replays the screen and, in the status line, looked
        // like a counter going up on every tap.
        if (id == _activeSessionId.value && socket != null && _state.value == TermState.OPEN) return
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
        // End the shell, not just the stream. Leaving it attached only hangs up;
        // the engine keeps the shell and its slot alive. The control frame goes
        // out before the socket is closed below.
        if (id.isNotBlank()) {
            socket?.send("""{"type":"close"}""")
        }
        retarget = ""
        _activeSessionId.value = ""
        scope.launch {
            if (id.isNotBlank()) {
                sessionModes.remove(id)
                settings.removeTerminalSessionId(id)
                // If the socket was already gone the engine never saw close.
                runCatching { history.closeTerminal(id) }
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
    @Synchronized
    fun close() {
        wanted = false
        closing = true
        dialJob?.cancel()
        dialJob = null
        refreshJob?.cancel()
        refreshJob = null
        closeSocket()
        _state.value = TermState.CLOSED
    }

    private fun closeSocket() {
        // Invalidate every callback from the socket being replaced before it is
        // closed; a late onClosed must not finish the next dial's deferreds.
        connection.incrementAndGet()
        val old = socket
        socket = null
        old?.close(1000, "client closed")
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
     * something and does not need to be poked.
     */
    fun isLive(): Boolean =
        wanted && (_state.value == TermState.OPEN || _state.value == TermState.CONNECTING)

    /**
     * Keystrokes to the shell, exactly as typed: control characters are bytes,
     * not commands (Ctrl-C is 0x03). False when the stream is not up.
     */
    fun send(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || _state.value != TermState.OPEN) return false
        val ws = socket ?: return false
        return ws.send(bytes.toByteString())
    }

    /** Tells the shell its window changed, so full-screen programs redraw. */
    fun resize(cols: Int, rows: Int): Boolean {
        if (cols <= 0 || rows <= 0) return false
        // The view reports its size as soon as it is laid out, which is usually
        // before the stream is up. Keeping it means the shell is never left at
        // the default 80x24 while the screen draws something else.
        pendingSize = cols to rows
        if (_state.value != TermState.OPEN) return false
        val ws = socket ?: return false
        return ws.send(resizeFrame(cols, rows))
    }

    /**
     * Aligns the tab list and pid labels with the engine's live shells. A null
     * list (engine unreachable or an old engine without /v1/terminals) leaves
     * the saved tabs alone.
     *
     * A shell id is not a shell: after the engine restarts, the same saved id
     * would make acquireSession start a brand-new shell under the tab the user
     * thought was still running. Dropping ids the engine no longer reports is
     * what makes a reconnect land on the right shell — or on a new tab when
     * that shell really is gone.
     */
    private suspend fun syncSessions(): Boolean =
        sessionSync.withLock {
            val rows = withTimeoutOrNull(5_000) { history.terminalList() } ?: return@withLock false

            val ids = mutableListOf<String>()
            val pids = mutableMapOf<String, Int>()
            for (o in rows) {
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
                val active = before.ifBlank { settings.currentTerminalSessionId() }
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
     * looks disconnected.
     */
    private suspend fun refreshDaemonAddress(): Boolean {
        val current = try {
            settings.current()
        } catch (t: Throwable) {
            return false
        }
        if (current.token.isBlank()) return false
        val node = try {
            withTimeoutOrNull(5_000) { history.node() }
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
        var waitedForSize = 0L
        // Reconcile the tabs once for this open. Re-reading the list before
        // every retry made a dropped link wait on D1 before it was even allowed
        // to dial again; on a reconnect the shell id is already known, and the
        // periodic refresh can correct the tab row afterwards.
        var syncAttempted = sessionsSynced
        while (!closing) {
            // The view reports its real grid on its first layout, usually a
            // frame after open(). Let that land before the open frame is sent,
            // so a reattach does not briefly force the 80x24 placeholder onto
            // a full-screen program. A view that never measures is not allowed
            // to hold the terminal hostage.
            if (pendingSize == null && waitedForSize < 1_000L) {
                delay(16)
                waitedForSize += 16
                continue
            }
            val now = System.currentTimeMillis()
            if (now - lastAddressCheck >= DAEMON_RECHECK_MS) {
                lastAddressCheck = now
                refreshDaemonAddress()
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
                _error.value = com.tulipskun.aixodia.R.string.terminal_no_address
                _state.value = TermState.CLOSED
                _detail.value = "token blank in settings"
                return
            }
            val base = terminalUrlFor(c.daemonUrl)
            if (base.isBlank()) {
                // The token is set but the address is not known yet: the app
                // opened before discovery found D1. Keep waiting.
                _detail.value = "daemon address unknown; waiting for discovery"
                delay(RETRY_MS)
                continue
            }

            // Reconcile the tabs with the shells the engine actually holds. A
            // saved id that survived an engine restart would otherwise be
            // reattached into a brand-new shell under an old tab.
            if (!syncAttempted) {
                syncAttempted = true
                syncSessions()
            }

            val wantedId = retarget ?: _activeSessionId.value
            // The view usually has a real size by now. A placeholder is only a
            // fallback for the first frame before Android measures the view.
            val (cols, rows) = pendingSize ?: (80 to 24)
            _detail.value = "opening terminal stream"
            val ticket = try {
                withTimeoutOrNull(8_000) { history.terminalTicket() }
            } catch (t: Throwable) {
                Log.w(TAG, "terminal ticket failed", t)
                null
            }
            if (ticket.isNullOrBlank()) {
                _detail.value = "could not get a terminal ticket"
                delay(backoff)
                backoff = minOf(backoff * 2, 10_000L)
                continue
            }

            val opened = CompletableDeferred<Boolean>()
            val ended = CompletableDeferred<Boolean>()
            val readySeen = AtomicBoolean(false)
            val token = connection.incrementAndGet()
            val listener = object : WebSocketListener() {
                private fun stale(): Boolean = token != connection.get()

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (stale()) return
                    _detail.value = "connected; waiting for the shell"
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (stale()) return
                    when (val ev = parseEvent(text)) {
                        is TermEvent.Ready -> {
                            readySeen.set(true)
                            opened.complete(true)
                            if (ev.id.isNotBlank()) {
                                // Synchronously, so a retry inside dial() and
                                // the tabs see the id before the DataStore write.
                                retarget = ev.id
                                _activeSessionId.value = ev.id
                                _sessions.value = (_sessions.value + ev.id).distinct()
                                if (ev.pid > 0) {
                                    _sessionPids.value = _sessionPids.value + (ev.id to ev.pid)
                                }
                                scope.launch {
                                    settings.saveTerminalSessionId(ev.id)
                                    settings.addTerminalSessionId(ev.id)
                                    if (ev.pid > 0) settings.saveTerminalSessionPid(ev.id, ev.pid)
                                    _sessions.value = settings.currentTerminalSessionIds()
                                    _sessionPids.value = settings.currentTerminalSessionPids()
                                }
                            }
                            _state.value = TermState.OPEN
                            _error.value = 0
                            _detail.value = "shell ${ev.shell.ifBlank { "?" }} at ${ev.cwd}"
                            _events.trySend(ev)
                        }

                        is TermEvent.Exit -> {
                            _state.value = TermState.ENDED
                            _events.trySend(ev)
                            opened.complete(true)
                            ended.complete(true)
                        }

                        is TermEvent.Failed -> {
                            _state.value = TermState.ENDED
                            _events.trySend(ev)
                            opened.complete(true)
                            ended.complete(true)
                        }

                        // An unreadable control frame is not worth ending a
                        // working shell over; the bytes keep flowing either way.
                        null -> Log.w(TAG, "unreadable control frame: ${text.take(120)}")
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (stale()) return
                    _output.trySend(bytes.toByteArray())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (stale()) return
                    if (readySeen.get()) {
                        ended.complete(false)
                    } else {
                        opened.complete(false)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (stale()) return
                    Log.w(TAG, "terminal socket failed: ${t.message}", t)
                    _detail.value = "connection failed: ${t.message.orEmpty()}"
                    if (readySeen.get()) {
                        ended.complete(false)
                    } else {
                        opened.complete(false)
                    }
                }
            }

            val url = buildString {
                append(base)
                append("?ticket=")
                append(URLEncoder.encode(ticket, "UTF-8"))
                if (wantedId.isNotBlank()) {
                    append("&id=")
                    append(URLEncoder.encode(wantedId, "UTF-8"))
                }
                append("&cols=").append(cols)
                append("&rows=").append(rows)
            }
            val ws = try {
                client.newWebSocket(Request.Builder().url(url).build(), listener)
            } catch (t: Throwable) {
                Log.w(TAG, "could not open terminal socket", t)
                _detail.value = "invalid terminal address"
                delay(backoff)
                backoff = minOf(backoff * 2, 10_000L)
                continue
            }
            socket = ws

            // The engine answers an upgrade with ready (or an error frame) at
            // once. A silent socket is a dead tunnel, not a slow shell.
            val answered = withTimeoutOrNull(READY_TIMEOUT) { opened.await() }
            if (answered == null) {
                _detail.value = "no answer from the engine; redialing"
                if (socket === ws) {
                    connection.incrementAndGet()
                    socket = null
                }
                ws.cancel()
                delay(RETRY_MS)
                continue
            }
            if (!answered) {
                // The handshake failed or the socket closed before ready. It
                // has already reported why; wait a little longer each time.
                if (socket === ws) socket = null
                ws.cancel()
                delay(backoff)
                backoff = minOf(backoff * 2, 10_000L)
                continue
            }

            backoff = RETRY_MS
            // The view can be laid out while the socket handshake is in
            // flight. Its resize was stored but could not be sent before the
            // ready frame; now that the shell exists, send the latest size so
            // the pty is not stuck at the size measured a frame earlier.
            sizeToResend(pendingSize, cols, rows)?.let { (latestCols, latestRows) ->
                ws.send(resizeFrame(latestCols, latestRows))
            }
            val shellEnded = ended.await()
            if (socket === ws) {
                // The shell's exit frame does not itself close the WebSocket.
                // Drop it here instead of leaving an idle socket behind the
                // "session ended" screen.
                connection.incrementAndGet()
                socket = null
                ws.close(1000, "shell ended")
            }
            if (closing) return
            if (shellEnded) return
            // The link dropped under a window that is still open — the app was
            // backgrounded, the engine moved, Wi-Fi changed. Reconnect instead
            // of leaving the panel on a dead connection.
            _state.value = TermState.CONNECTING
            _error.value = 0
            _detail.value = "disconnected; reconnecting"
            delay(RETRY_MS)
        }
    }

    companion object {
        /**
         * Maps an HTTP(S) daemon address to the dedicated terminal WebSocket.
         * The old `/ws` room URL is accepted too, so starting points that still
         * hand one over keep working.
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
 * can finish while the socket is being opened; resize() stores that size but
 * cannot send it until the stream is up.
 */
internal fun sizeToResend(
    pending: Pair<Int, Int>?,
    sentCols: Int,
    sentRows: Int,
): Pair<Int, Int>? = pending?.takeIf { (cols, rows) ->
    cols > 0 && rows > 0 && (cols != sentCols || rows != sentRows)
}

/** Parses one control frame; null when it is not one we know. */
internal fun parseEvent(text: String): TermEvent? {
    val f = try {
        JSONObject(text)
    } catch (_: Exception) {
        return null
    }
    return when (f.optString("type")) {
        "ready" -> TermEvent.Ready(
            shell = f.optString("shell"),
            cwd = f.optString("cwd"),
            cols = f.optInt("cols", 0),
            rows = f.optInt("rows", 0),
            id = f.optString("id"),
            pid = f.optInt("pid", 0),
        )
        "exit" -> TermEvent.Exit(f.optInt("code", 0))
        "error" -> TermEvent.Failed(f.optString("error"))
        else -> null
    }
}

/** `{"type":"resize","cols":100,"rows":30}` — both sizes are always sent. */
internal fun resizeFrame(cols: Int, rows: Int): String =
    JSONObject().put("type", "resize").put("cols", cols).put("rows", rows).toString()

/**
 * Whether returning to the foreground should rebuild an existing stream.
 * Shared with the panel's lifecycle handling.
 */
internal fun shouldReattachAfterBackground(state: TermState, wanted: Boolean, awayMs: Long): Boolean =
    wanted && state == TermState.OPEN && awayMs >= REATTACH_AFTER_BACKGROUND_MS
