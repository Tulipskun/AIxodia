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
 * everything the shell prints travels as raw bytes (see [TerminalSocket.outputFor]).
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

/** A control frame tagged with the tab it belongs to. */
data class TabEvent(val sessionId: String, val event: TermEvent)

/**
 * The terminal's tabs, each carried by its own WebSocket to the engine's
 * `/terminal` endpoint.
 *
 * A tab is a shell on the engine, keyed by the persisted session id. All live
 * tabs stay attached for as long as this socket is owned by the chat screen:
 * switching a tab only changes which emulator is on screen, it never hangs up
 * and redials. Leaving the terminal for the chat only hides the window, so the
 * same tabs and streams are still there when it is shown again. The app itself
 * still draws every cell with its own [TerminalEmulator]; there is no WebView
 * and no JavaScript terminal.
 *
 * The socket is authenticated with a single-use, short-lived ticket fetched
 * over the normal authenticated HTTP path, so the long-lived API token never
 * appears in a URL. Closing the terminal's tabs (or the process going away)
 * hangs up the streams; the shells themselves stay on the engine (in tmux when
 * available), ready for a replay on the next attach.
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

    // Every tab dials at once, but rediscovering the daemon address is one job.
    private val addressSync = Mutex()
    @Volatile
    private var lastAddressCheck = 0L

    private val _state = MutableStateFlow(TermState.CLOSED)
    val state: StateFlow<TermState> = _state

    /** Known session ids on the engine, in creation order. */
    private val _sessions = MutableStateFlow<List<String>>(emptyList())
    val sessions: StateFlow<List<String>> = _sessions

    /** The pid the engine named for each known session id. */
    private val _sessionPids = MutableStateFlow<Map<String, Int>>(emptyMap())
    val sessionPids: StateFlow<Map<String, Int>> = _sessionPids

    /** The id of the tab on screen (empty while a brand-new shell starts). */
    private val _activeSessionId = MutableStateFlow("")
    val activeSessionId: StateFlow<String> = _activeSessionId

    /** A string resource id for why the active tab is not up; 0 when nothing is wrong. */
    private val _error = MutableStateFlow(0)
    val error: StateFlow<Int> = _error

    /** Detailed, English, human-readable status for the panel — no logcat needed. */
    private val _detail = MutableStateFlow("")
    val detail: StateFlow<String> = _detail

    /**
     * Control frames from every tab, tagged with its session id. The panel only
     * acts on the tab it is showing; [lastEvent] lets it restore the exit
     * banner when the user switches back.
     */
    private val _events = Channel<TabEvent>(Channel.UNLIMITED)
    val events: Flow<TabEvent> = _events.receiveAsFlow()

    /**
     * The last modes seen for a session. The engine replays only the tail of a
     * session's output, so a window that reattaches to a running program gets
     * pixels but not always every mode sequence. The panel puts these back
     * before the replay is drawn.
     */
    private val sessionModes = ConcurrentHashMap<String, TerminalModes>()

    // All live tabs, keyed by session id (the empty string is the one pending
    // new shell). Guarded by this object's monitor.
    private val links = HashMap<String, TabLink>()
    private var activeLink: TabLink? = null

    // The one reconcile/dial coroutine. open() and close() cancel it before
    // starting a new one, so a stream that is being replaced can never race its
    // successor back to life.
    private var dialJob: Job? = null

    // The periodic tab refresh, so a slow list call cannot queue one per tick.
    private var refreshJob: Job? = null

    // Whether the window wants its terminals. open() sets it, close() clears it.
    @Volatile
    private var wanted = false

    // Whether the app is on screen. Backgrounding only stops the periodic tab
    // list poll; the foreground-service-held WebSockets stay attached.
    @Volatile
    private var appVisible = true

    // Whether an empty tab list should start a fresh shell. Opening the window
    // (and losing every shell) does; closing the last tab on purpose does not,
    // because that screen is showing its finished state and offers "open new".
    @Volatile
    private var autoNew = true

    /** The last size the view reported, sent to every stream when it opens. */
    @Volatile
    private var pendingSize: Pair<Int, Int>? = null

    fun rememberedModes(id: String): TerminalModes? =
        if (id.isBlank()) null else sessionModes[id]

    fun rememberModes(id: String, modes: TerminalModes) {
        if (id.isNotBlank()) sessionModes[id] = modes
    }

    /**
     * One tab's output. The channel is created before the dial starts and is
     * kept across reconnects, so a late reader still receives the engine's
     * replay. Returns null until that tab's link exists.
     */
    fun outputFor(id: String): Flow<ByteArray>? = synchronized(this) {
        links[id]?.output?.receiveAsFlow()
    }

    /** The state of one tab, for the tab list and for restoring the header. */
    fun tabState(id: String): TermState? = synchronized(this) { links[id]?.state?.value }

    /** The ready frame of one tab, if it has attached. */
    fun tabReady(id: String): TermEvent.Ready? = synchronized(this) { links[id]?.readyEvent }

    /** The last exit/failure of one tab, so switching back restores its banner. */
    fun lastEvent(id: String): TermEvent? = synchronized(this) { links[id]?.lastEvent }

    /** Opens every saved tab, if the window is not already up. */
    @Synchronized
    fun open() {
        wanted = true
        autoNew = true
        // A window that is already dialing or up owns its links. This also makes
        // the initial open and the lifecycle's start callback harmless together.
        if (links.isNotEmpty()) {
            if (activeLink == null) activeLink = links.values.firstOrNull()
            publishActive()
            return
        }
        dialJob?.cancel()
        _error.value = 0
        // Events from a previous open are still sitting in the unbounded
        // channel if nobody was collecting when they arrived. Draining them
        // keeps a reopened window from instantly showing the old exit.
        drainEvents()
        _state.value = TermState.CONNECTING
        dialJob = scope.launch {
            loadSavedTabs()
            if (!wanted) return@launch
            syncSessions()
            if (!wanted) return@launch
            reconcileLinks()
        }
    }

    /**
     * Re-dials every tab now, without waiting for the current links to fail.
     * Used when the app returns to the foreground after a long absence, or when
     * discovery has just moved the engine address.
     */
    fun reconnect() {
        if (!wanted) return
        synchronized(this) {
            links.values.forEach { if (it.state.value != TermState.ENDED) it.reconnect() }
        }
    }

    /**
     * Re-reads the engine's list of live shells while the window is open. A
     * shell can be killed from another terminal, or another phone can open one,
     * long after the initial dial.
     */
    @Synchronized
    fun refreshSessions() {
        if (!wanted || !appVisible) return
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            syncSessions()
            if (!wanted || !appVisible) return@launch
            reconcileLinks()
        }
    }

    /**
     * The activity came on or went off screen. Backgrounding must not hang up
     * anything: the foreground service keeps the process and every tab's
     * WebSocket alive, so returning to the app is instant. It only stops the
     * five-second tab-list poll while nobody can see the list.
     */
    fun setAppVisible(visible: Boolean) {
        appVisible = visible
        if (!visible) {
            refreshJob?.cancel()
            refreshJob = null
        } else if (wanted) {
            refreshSessions()
        }
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

    /**
     * Switches the visible tab. The other tabs keep their sockets and emulators
     * alive; this never hangs up or replays the tab being left.
     */
    @Synchronized
    fun switchSession(id: String) {
        if (id.isBlank()) return
        if (activeLink?.id == id) return
        val link = links[id] ?: createLink(id).also { it.open() }
        activate(link)
    }

    /**
     * Adds a brand-new shell and makes it the visible tab. The shells already
     * open stay attached; only their tab labels are left behind.
     */
    @Synchronized
    fun newSession() {
        autoNew = true
        // An ended tab is not a place a new shell can run. Drop its stale link
        // (the shell is already gone) before making the new one.
        activeLink?.takeIf { it.state.value == TermState.ENDED }?.let { dropLink(it, kill = false) }

        val pending = links[""]
        if (pending != null && pending.state.value != TermState.ENDED) {
            activate(pending)
            pending.open()
            return
        }
        if (pending != null) dropLink(pending, kill = false)

        val link = TabLink("")
        links[""] = link
        activate(link)
        link.open()
    }

    /**
     * Kills the visible shell and forgets its id. Other tabs stay attached; if
     * one is left it becomes visible, otherwise the panel shows the finished
     * state and waits for "open new".
     */
    @Synchronized
    fun closeSession() {
        val link = activeLink ?: return
        val id = link.id
        // End the shell, not just the stream. The control frame goes out before
        // the socket is closed below.
        if (id.isNotBlank()) link.sendClose()
        dropLink(link, kill = true)

        val nextId = _sessions.value.lastOrNull { candidate ->
            candidate != id && links[candidate]?.state?.value != TermState.ENDED
        }
        val next = nextId?.let { links[it] }
        if (next != null) {
            activate(next)
        } else {
            autoNew = false
            _activeSessionId.value = ""
            _error.value = 0
            _detail.value = ""
            _state.value = TermState.ENDED
            scope.launch { settings.clearTerminalSessionId() }
        }
    }

    /** Hangs up every tab. The engine keeps the shells for a later reattach. */
    @Synchronized
    fun close() {
        wanted = false
        dialJob?.cancel()
        dialJob = null
        refreshJob?.cancel()
        refreshJob = null
        links.values.forEach { it.pause() }
        links.clear()
        activeLink = null
        _state.value = TermState.CLOSED
    }

    /**
     * The window was closed on purpose: end the shells, not just the streams.
     *
     * Going back to the chat only hides the window, so a trip back keeps every
     * socket attached. This is the explicit Close action: every tab is ended,
     * the saved list is cleared, and the next open starts on a fresh shell
     * instead of handing back tabs the user thought they had closed.
     */
    @Synchronized
    fun endAllTabs() {
        wanted = false
        dialJob?.cancel()
        dialJob = null
        refreshJob?.cancel()
        refreshJob = null
        // dropLink issues the engine-side close for every id (the control frame
        // may have no socket behind it if the tab had already ended).
        links.values.toList().forEach { dropLink(it, kill = true) }
        links.clear()
        activeLink = null
        _sessions.value = emptyList()
        _sessionPids.value = emptyMap()
        _activeSessionId.value = ""
        _state.value = TermState.CLOSED
        _error.value = 0
        _detail.value = ""
        scope.launch {
            // dropLink already removes each id; these make sure nothing is left
            // behind if a tab had no id yet.
            settings.saveTerminalSessionIds(emptyList())
            settings.saveTerminalSessionPids(emptyMap())
            settings.clearTerminalSessionId()
        }
    }

    /**
     * True while any tab is dialing or up — the terminal is doing something and
     * does not need to be poked.
     */
    fun isLive(): Boolean {
        if (!wanted) return false
        return synchronized(this) {
            links.values.any {
                it.state.value == TermState.OPEN || it.state.value == TermState.CONNECTING
            }
        }
    }

    /**
     * Keystrokes to the active shell, exactly as typed: control characters are
     * bytes, not commands (Ctrl-C is 0x03). False when that tab is not up.
     */
    fun send(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val link = synchronized(this) { activeLink } ?: return false
        return link.send(bytes)
    }

    /** Tells every live shell its window changed, so full-screen programs redraw. */
    fun resize(cols: Int, rows: Int): Boolean {
        if (cols <= 0 || rows <= 0) return false
        // The view reports its size as soon as it is laid out, which is usually
        // before the streams are up. Keeping it means every shell is never left
        // at the default 80x24. Every tab gets the same size because every tab
        // is shown in the same view.
        pendingSize = cols to rows
        var sent = false
        synchronized(this) {
            links.values.forEach { if (it.resize(cols, rows)) sent = true }
        }
        return sent
    }

    // =======================================================================
    // tab reconciliation
    // =======================================================================

    private suspend fun loadSavedTabs() {
        val savedActive = try {
            settings.currentTerminalSessionId()
        } catch (t: Throwable) {
            Log.w(TAG, "settings unreadable", t)
            ""
        }
        val savedIds = try {
            settings.currentTerminalSessionIds()
        } catch (t: Throwable) {
            emptyList()
        }
        val savedPids = try {
            settings.currentTerminalSessionPids()
        } catch (t: Throwable) {
            emptyMap()
        }
        _activeSessionId.value = savedActive
        _sessions.value = savedIds
        _sessionPids.value = savedPids
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

            // A closed shell can still be listed by the engine for a moment
            // (the control frame and the DELETE are in flight). Keeping it out
            // of the tab list is what stops a closed tab from reattaching; the
            // tombstone is dropped as soon as the engine agrees it is gone.
            closingIds.retainAll(ids.toSet())
            ids.removeAll(closingIds)
            pids.keys.retainAll(ids.toSet())

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

            val before = _activeSessionId.value
            // A brand-new shell that has not answered ready yet is an explicit
            // choice, not a missing active tab. Do not switch away from it when
            // the periodic list still lacks its id.
            val pendingNew = synchronized(this) {
                activeLink?.id == "" && activeLink?.state?.value != TermState.ENDED
            }
            if (!pendingNew) {
                val active = before.ifBlank { settings.currentTerminalSessionId() }
                _activeSessionId.value = when {
                    active.isNotBlank() && active in ids -> active
                    ids.isNotEmpty() -> ids.last()
                    else -> ""
                }
            }
            before.isNotBlank() && before !in ids
        }

    /**
     * Brings the live links in line with [sessions]: stale sockets are hung up,
     * missing tabs are attached, and the visible tab is kept if it still
     * exists.
     */
    @Synchronized
    private fun reconcileLinks() {
        if (!wanted) return
        val ids = _sessions.value

        // The engine no longer has these shells. Hang up the links; the shells
        // are already gone, so this only prevents dialing a dead id.
        links.values.toList().forEach { link ->
            if (link.id.isNotBlank() && link.id !in ids) {
                link.pause()
                links.remove(link.id)
                if (activeLink === link) activeLink = null
                sessionModes.remove(link.id)
                _sessionPids.value = _sessionPids.value - link.id
            }
        }
        // A pending shell that failed to start cannot be retried by the list.
        links[""]?.let { link ->
            if (link.state.value == TermState.ENDED && link !== activeLink) {
                link.pause()
                links.remove("")
            }
        }

        if (ids.isEmpty()) {
            val pending = links[""]
            if (pending == null) {
                if (!autoNew) return
                val link = TabLink("")
                links[""] = link
                activate(link)
                link.open()
            } else {
                if (pending.state.value == TermState.CLOSED) pending.open()
                if (activeLink == null) activate(pending)
            }
            return
        }

        ids.forEach { id ->
            val link = links[id]
            when {
                link == null -> createLink(id).open()
                link.state.value == TermState.CLOSED -> link.open()
            }
        }

        val current = activeLink
        val chosen = when {
            current?.id == "" && current.state.value != TermState.ENDED -> current
            current != null && current.id in ids -> current
            _activeSessionId.value in ids -> links[_activeSessionId.value]
            else -> links[ids.last()]
        }
        activate(chosen)
    }

    private fun createLink(id: String): TabLink {
        val link = TabLink(id)
        links[id] = link
        return link
    }

    @Synchronized
    private fun activate(link: TabLink?) {
        if (activeLink === link) {
            publishActive()
            return
        }
        activeLink = link
        _activeSessionId.value = link?.id.orEmpty()
        publishActive()
        val id = link?.id.orEmpty()
        scope.launch {
            if (id.isBlank()) settings.clearTerminalSessionId()
            else settings.saveTerminalSessionId(id)
        }
    }

    @Synchronized
    private fun publishActive() {
        val link = activeLink
        _state.value = link?.state?.value ?: TermState.CLOSED
        _error.value = link?.error ?: 0
        _detail.value = link?.detail.orEmpty()
    }

    /** A link changed one of its visible fields; mirror it when it is active. */
    @Synchronized
    private fun onLinkChanged(link: TabLink) {
        if (activeLink === link) publishActive()
    }

    private fun dropLink(link: TabLink, kill: Boolean) {
        val id = link.id
        link.pause()
        links.remove(id)
        if (activeLink === link) activeLink = null
        if (id.isNotBlank()) {
            // Remember that this id is on its way out, so the next list poll
            // cannot attach it again while the engine is still tearing the
            // shell down.
            if (kill) closingIds.add(id)
            _sessions.value = _sessions.value - id
            _sessionPids.value = _sessionPids.value - id
            sessionModes.remove(id)
        }
        scope.launch {
            if (id.isNotBlank()) {
                settings.removeTerminalSessionId(id)
                // If the socket was already gone the engine never saw close.
                if (kill) runCatching { history.closeTerminal(id) }
            }
        }
    }

    // =======================================================================
    // one tab's stream
    // =======================================================================

    /**
     * One shell and its WebSocket. Output goes to a channel that survives
     * reconnects; the panel owns the emulator that drains it.
     */
    private inner class TabLink(var id: String) {
        val output = Channel<ByteArray>(Channel.UNLIMITED)
        private val _state = MutableStateFlow(TermState.CLOSED)
        val state: StateFlow<TermState> = _state

        @Volatile
        var error = 0
        @Volatile
        var detail = ""
        @Volatile
        var readyEvent: TermEvent.Ready? = null
        @Volatile
        var lastEvent: TermEvent? = null
        @Volatile
        var closing = false
        @Volatile
        var wanted = false
        @Volatile
        var socket: WebSocket? = null

        private var job: Job? = null
        val connection = AtomicInteger(0)

        fun setState(next: TermState) {
            if (_state.value == next) return
            _state.value = next
            onLinkChanged(this)
        }

        fun updateDetail(next: String) {
            detail = next
            onLinkChanged(this)
        }

        fun updateError(next: Int) {
            error = next
            onLinkChanged(this)
        }

        fun open() {
            wanted = true
            closing = false
            job?.cancel()
            closeSocket()
            _state.value = TermState.CONNECTING
            onLinkChanged(this)
            job = scope.launch { dialLink(this@TabLink) }
        }

        fun reconnect() {
            if (!wanted) return
            open()
        }

        fun pause() {
            wanted = false
            closing = true
            job?.cancel()
            job = null
            closeSocket()
            _state.value = TermState.CLOSED
            onLinkChanged(this)
        }

        fun closeSocket() {
            // Invalidate every callback from the socket being replaced before
            // it is closed; a late onClosed must not finish the next attempt's
            // deferreds.
            connection.incrementAndGet()
            val old = socket
            socket = null
            old?.close(1000, "client closed")
        }

        fun send(bytes: ByteArray): Boolean {
            if (_state.value != TermState.OPEN) return false
            val ws = socket ?: return false
            return ws.send(bytes.toByteString())
        }

        fun resize(cols: Int, rows: Int): Boolean {
            if (_state.value != TermState.OPEN) return false
            val ws = socket ?: return false
            return ws.send(resizeFrame(cols, rows))
        }

        fun sendClose() {
            socket?.send("""{"type":"close"}""")
        }
    }

    private suspend fun dialLink(link: TabLink) {
        var backoff = RETRY_MS
        var waitedForSize = 0L
        while (wanted && link.wanted && !link.closing) {
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
            refreshDaemonAddressIfDue()
            val c = try {
                settings.current()
            } catch (t: Throwable) {
                // A read that fails is not "there is no daemon": DataStore can
                // hiccup. Retry rather than showing a dead end nobody can clear.
                Log.w(TAG, "settings unreadable", t)
                link.updateDetail("settings.current() threw: ${t.message}")
                delay(RETRY_MS)
                continue
            }
            if (c.token.isBlank()) {
                // Nothing to connect with: the sign-in gate has not been passed.
                link.updateError(com.tulipskun.aixodia.R.string.terminal_no_address)
                link.setState(TermState.CLOSED)
                link.updateDetail("token blank in settings")
                return
            }
            val base = terminalUrlFor(c.daemonUrl)
            if (base.isBlank()) {
                // The token is set but the address is not known yet: the app
                // opened before discovery found D1. Keep waiting.
                link.updateDetail("daemon address unknown; waiting for discovery")
                delay(RETRY_MS)
                continue
            }

            val wantedId = link.id
            // The view usually has a real size by now. A placeholder is only a
            // fallback for the first frame before Android measures the view.
            val (cols, rows) = pendingSize ?: (80 to 24)
            link.updateDetail("opening terminal stream")
            val ticket = try {
                withTimeoutOrNull(8_000) { history.terminalTicket() }
            } catch (t: Throwable) {
                Log.w(TAG, "terminal ticket failed", t)
                null
            }
            if (ticket.isNullOrBlank()) {
                link.updateDetail("could not get a terminal ticket")
                delay(backoff)
                backoff = minOf(backoff * 2, 10_000L)
                continue
            }

            val opened = CompletableDeferred<Boolean>()
            val ended = CompletableDeferred<Boolean>()
            val readySeen = AtomicBoolean(false)
            val token = link.connection.incrementAndGet()
            val listener = object : WebSocketListener() {
                private fun stale(): Boolean = token != link.connection.get()

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (stale()) return
                    link.updateDetail("connected; waiting for the shell")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (stale()) return
                    when (val ev = parseEvent(text)) {
                        is TermEvent.Ready -> {
                            readySeen.set(true)
                            opened.complete(true)
                            onReady(link, ev)
                        }

                        is TermEvent.Exit -> {
                            link.lastEvent = ev
                            link.setState(TermState.ENDED)
                            _events.trySend(TabEvent(link.id, ev))
                            opened.complete(true)
                            ended.complete(true)
                        }

                        is TermEvent.Failed -> {
                            link.lastEvent = ev
                            link.setState(TermState.ENDED)
                            _events.trySend(TabEvent(link.id, ev))
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
                    link.output.trySend(bytes.toByteArray())
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
                    link.updateDetail("connection failed: ${t.message.orEmpty()}")
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
                link.updateDetail("invalid terminal address")
                delay(backoff)
                backoff = minOf(backoff * 2, 10_000L)
                continue
            }
            link.socket = ws

            // The engine answers an upgrade with ready (or an error frame) at
            // once. A silent socket is a dead tunnel, not a slow shell.
            val answered = withTimeoutOrNull(READY_TIMEOUT) { opened.await() }
            if (answered == null) {
                link.updateDetail("no answer from the engine; redialing")
                if (link.socket === ws) {
                    link.connection.incrementAndGet()
                    link.socket = null
                }
                ws.cancel()
                delay(RETRY_MS)
                continue
            }
            if (!answered) {
                // The handshake failed or the socket closed before ready. It
                // has already reported why; wait a little longer each time.
                if (link.socket === ws) link.socket = null
                ws.cancel()
                delay(backoff)
                backoff = minOf(backoff * 2, 10_000L)
                continue
            }

            backoff = RETRY_MS
            // The view can be laid out while the socket handshake is in
            // flight. Its resize was stored but could not be sent before the
            // ready frame; now that the shell exists, send the latest size.
            sizeToResend(pendingSize, cols, rows)?.let { (latestCols, latestRows) ->
                ws.send(resizeFrame(latestCols, latestRows))
            }
            val shellEnded = ended.await()
            if (link.socket === ws) {
                // The shell's exit frame does not itself close the WebSocket.
                // Drop it here instead of leaving an idle socket behind the
                // "session ended" screen.
                link.connection.incrementAndGet()
                link.socket = null
                ws.close(1000, "shell ended")
            }
            if (wanted.not() || !link.wanted || link.closing) return
            if (shellEnded) return
            // The link dropped under a window that is still open — the app was
            // backgrounded, the engine moved, Wi-Fi changed. Reconnect instead
            // of leaving the tab on a dead connection.
            link.setState(TermState.CONNECTING)
            link.updateError(0)
            link.updateDetail("disconnected; reconnecting")
            delay(RETRY_MS)
        }
    }

    private fun onReady(link: TabLink, ev: TermEvent.Ready) {
        val newId = ev.id
        synchronized(this) {
            if (newId.isNotBlank() && link.id != newId) {
                val old = link.id
                if (old.isNotBlank() && links[old] === link) links.remove(old)
                if (links[""] === link) links.remove("")
                link.id = newId
                links[newId] = link
                _sessions.value = (_sessions.value + newId).distinct()
            }
            if (ev.pid > 0 && link.id.isNotBlank()) {
                _sessionPids.value = _sessionPids.value + (link.id to ev.pid)
            }
            link.readyEvent = ev
            link.lastEvent = ev
            link.error = 0
            link.detail = "shell ${ev.shell.ifBlank { "?" }} at ${ev.cwd}"
            if (activeLink === link) _activeSessionId.value = link.id
            publishActive()
        }
        if (newId.isNotBlank()) {
            scope.launch {
                settings.saveTerminalSessionId(newId)
                settings.addTerminalSessionId(newId)
                if (ev.pid > 0) settings.saveTerminalSessionPid(newId, ev.pid)
                _sessions.value = settings.currentTerminalSessionIds()
                _sessionPids.value = settings.currentTerminalSessionPids()
            }
        }
        link.setState(TermState.OPEN)
        _events.trySend(TabEvent(link.id, ev))
    }

    private fun drainEvents() {
        while (_events.tryReceive().isSuccess) { /* drop stale events */ }
    }

    /**
     * Moves the stored address to the live daemon before a tab dials.
     *
     * Discovery owns this normally, but its next tick can be up to 30s away
     * (longer after a failed read). During that gap a terminal opened right
     * after an engine self-update keeps dialing the dead quick-tunnel host and
     * looks disconnected.
     */
    private suspend fun refreshDaemonAddressIfDue() {
        if (!addressSync.tryLock()) return
        try {
            val now = System.currentTimeMillis()
            if (now - lastAddressCheck < DAEMON_RECHECK_MS) return
            lastAddressCheck = now
            refreshDaemonAddress()
        } finally {
            addressSync.unlock()
        }
    }

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

    companion object {
        /**
         * Ids the user closed on purpose, until the engine's list stops naming
         * them. A close is a control frame and a DELETE, not an instant: the
         * next list poll can still report the shell, and without this the tab
         * came back right after it was closed. It is shared by every socket in
         * the process because the window is rebuilt on reopen, and an id is
         * only forgotten once the engine agrees it is gone.
         */
        private val closingIds: MutableSet<String> =
            java.util.concurrent.ConcurrentHashMap.newKeySet()

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
