package com.tulipskun.aixodia.data.remote

import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.tulipskun.aixodia.R
import com.tulipskun.aixodia.SettingsStore
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

private const val TAG = "TerminalSocket"

/** The gap between open attempts. Discovery polls every 30s, so this covers it. */
private const val RETRY_MS = 1_500L

/** How one dial ended, so the loop knows whether to retry. */
private enum class DialOutcome { OPEN, RETRY, TOKEN_REJECTED }

/** Where the terminal window stands, for the header line. */
enum class TermState { CLOSED, CONNECTING, OPEN, ENDED }

/**
 * What the engine says on the text channel. These are control frames only —
 * everything the shell prints travels as raw bytes (see [TerminalSocket.output]).
 */
sealed interface TermEvent {
    /** The shell is up: what it is and where it starts. */
    data class Ready(val shell: String, val cwd: String, val cols: Int, val rows: Int) : TermEvent

    /** The shell ended on its own, with this exit code. The socket closes after. */
    data class Exit(val code: Int) : TermEvent

    /** Something failed after the handshake; the socket closes after this too. */
    data class Failed(val message: String) : TermEvent
}

/**
 * One terminal: its own WebSocket to the engine's `/terminal`, its own shell on
 * the host, its own life.
 *
 * Separate from [AiDirectSocket] on purpose. That socket is the app's chat and
 * stays up for the whole run; this one exists only while the terminal window is
 * open, because every connection is a real shell process on the machine. Closing
 * the window hangs up, and the engine ends that shell and nothing else.
 */
class TerminalSocket(private val settings: SettingsStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resizeAdapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        .adapter(ResizeFrame::class.java)

    private val client = OkHttpClient.Builder()
        // The engine pings every 30s and Cloudflare's edge drops an idle socket
        // after about 100s. The app's own ping keeps a quiet terminal — sitting
        // at a prompt — comfortably alive from this side too.
        .pingInterval(java.time.Duration.ofSeconds(20))
        .build()

    private val _state = MutableStateFlow(TermState.CLOSED)
    val state: StateFlow<TermState> = _state

    /** A string resource id for why the terminal is not up; 0 when nothing is wrong. */
    private val _error = MutableStateFlow(0)
    val error: StateFlow<Int> = _error

    /** Detailed, English, human-readable status for the panel — no logcat needed. */
    private val _detail = MutableStateFlow("")
    val detail: StateFlow<String> = _detail

    // A channel, not a StateFlow: terminal output is a stream, and a dropped
    // frame would leave the screen showing something the shell never printed.
    // Nothing is consumed until the page can render it, so a shell that answers
    // before the WebView is ready is not lost.
    private val _output = Channel<ByteArray>(Channel.UNLIMITED)
    val output: Flow<ByteArray> = _output.receiveAsFlow()

    private val _events = Channel<TermEvent>(Channel.UNLIMITED)
    val events: Flow<TermEvent> = _events.receiveAsFlow()

    @Volatile
    private var ws: WebSocket? = null

    // The one dialing coroutine. open() and close() cancel it before starting a
    // new one, so a socket that is being replaced can never race its successor
    // back to life — the previous design let an old dial's late callbacks run
    // against the new session.
    private var dialJob: Job? = null

    // Whether the window wants a terminal. open() sets it, close() clears it.
    // Unlike [isLive] it stays true while dial() is still waiting for the daemon
    // address, which is the state a fresh sign-in starts in.
    @Volatile
    private var wanted = false

    // Touched from the socket's own callback thread as well as the UI's, so the
    // two flags that decide what a close means have to be visible across threads.
    @Volatile
    private var closing = false

    /** The last size the page asked for, resent when the socket comes up. */
    @Volatile
    private var pendingSize: Pair<Int, Int>? = null

    /** Dials the terminal, if it is not already dialing or up. */
    fun open() {
        wanted = true
        // A socket left over from a previous session that died silently (app
        // backgrounded, Doze, dropped Wi-Fi) would make this return without
        // reconnecting, leaving the terminal stuck on a dead connection. Only
        // skip dialing when the socket is genuinely open.
        if (ws != null && _state.value == TermState.OPEN) return
        // Stop the previous dialing coroutine and drop any socket it owned, so a
        // replaced socket can never race its successor back to life.
        dialJob?.cancel()
        ws?.let { ws = null; it.cancel() }
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
     * discovery has just moved the daemon address: the socket is pointed at the
     * old one and would otherwise sit there until its own retry.
     */
    fun reconnect() {
        if (!wanted) return
        reconnectLocked()
    }

    private fun reconnectLocked() {
        dialJob?.cancel()
        ws?.let { ws = null; it.cancel() }
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
     * error, where the socket is already gone and only a fresh connection can
     * give back a prompt.
     */
    fun restart() {
        close()
        open()
    }

    /** Hangs up. The engine kills that shell; nothing is left running. */
    fun close() {
        wanted = false
        closing = true
        dialJob?.cancel()
        dialJob = null
        ws?.close(1000, "window closed")
        ws = null
        _state.value = TermState.CLOSED
    }

    /**
     * True while a dial is in flight or a shell is up — the terminal is doing
     * something and does not need to be poked. Used when the app returns to the
     * foreground, where a socket that has quietly died should be redialed.
     */
    fun isLive(): Boolean =
        wanted && (_state.value == TermState.OPEN || _state.value == TermState.CONNECTING)

    /**
     * Keystrokes to the shell, exactly as typed: control characters are bytes,
     * not commands (Ctrl-C is 0x03). False when the socket is not up.
     */
    fun send(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        return ws?.send(ByteString.of(*bytes)) == true
    }

    /** Tells the shell its window changed, so full-screen programs redraw. */
    fun resize(cols: Int, rows: Int): Boolean {
        if (cols <= 0 || rows <= 0) return false
        // xterm reports its size as soon as the page is up, which is usually
        // before the handshake has finished. Keeping it means the shell is never
        // left at the default 80x24 while the screen draws something else.
        pendingSize = cols to rows
        val w = ws ?: return false
        return w.send(resizeFrame(cols, rows))
    }

    private fun resizeFrame(cols: Int, rows: Int): String =
        resizeAdapter.toJson(ResizeFrame(type = "resize", cols = cols, rows = rows))

    private suspend fun dial() {
        var backoff = RETRY_MS
        while (!closing) {
            val c = try {
                settings.current()
            } catch (t: Throwable) {
                // A read that fails is not "there is no daemon": DataStore can
                // hiccup, and a stored address may simply not have been read yet.
                // Retry rather than showing a dead end the user cannot clear.
                Log.w(TAG, "settings unreadable", t)
                _detail.value = "settings.current() threw: ${t.message}"
                delay(RETRY_MS)
                continue
            }
            val url = terminalUrlFor(c.wsUrl)
            if (c.token.isBlank()) {
                // Nothing to connect with: the sign-in gate has not been passed.
                // That is a real, actionable state, not discovery being slow.
                _error.value = R.string.terminal_no_address
                _state.value = TermState.CLOSED
                _detail.value = "token blank in settings"
                return
            }
            if (url.isBlank()) {
                // The token is set but the daemon address is not known yet: the
                // app opened before discovery finished reading D1. Keep waiting
                // for as long as the window is open — giving up here is what left
                // the terminal saying "no daemon address" while the chat, which
                // waits the same way, was already up. dial() re-reads settings on
                // every pass, so it connects the moment the address lands.
                _detail.value = "wsUrl empty (raw=${c.wsUrl}); waiting for discovery"
                delay(RETRY_MS)
                continue
            }

            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${c.token}")
                .build()
            val outcome = CompletableDeferred<DialOutcome>()
            _detail.value = "dialing $url"
            // Completed once an OPEN socket goes away: true when the shell ended
            // on its own (exit or error frame), false when the link dropped. That
            // is the difference between "show the result" and "dial again".
            val ended = CompletableDeferred<Boolean>()
            ws = client.newWebSocket(req, object : WebSocketListener() {
                // A socket that is no longer the current one must not touch shared
                // state. When the window is reopened the previous socket's
                // teardown callbacks arrive after the new dial has begun (or even
                // after it is OPEN); without this guard they would slam the fresh
                // session to ENDED and print "the connection closed" over a live
                // terminal.
                private fun stale(w: WebSocket): Boolean = ws !== w

                override fun onOpen(w: WebSocket, r: Response) {
                    if (stale(w)) return
                    _state.value = TermState.OPEN
                    _error.value = 0
                    _detail.value = "connected $url"
                    // The size the page already reported, now that there is a
                    // shell to tell it to.
                    pendingSize?.let { (cols, rows) -> w.send(resizeFrame(cols, rows)) }
                    outcome.complete(DialOutcome.OPEN)
                }

                override fun onMessage(w: WebSocket, bytes: ByteString) {
                    if (stale(w)) return
                    _output.trySend(bytes.toByteArray())
                }

                override fun onMessage(w: WebSocket, text: String) {
                    if (stale(w)) return
                    when (val ev = parseEvent(text)) {
                        is TermEvent.Exit -> {
                            // The shell finished. This is an outcome to show, not
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

                        is TermEvent.Ready -> _events.trySend(ev)

                        // An unreadable control frame is not worth ending a
                        // working shell over; the bytes keep flowing either way.
                        null -> Log.w(TAG, "unreadable control frame: ${text.take(120)}")
                    }
                }

                override fun onFailure(w: WebSocket, t: Throwable, r: Response?) {
                    Log.w(TAG, "terminal socket failure", t)
                    _detail.value = "onFailure: ${t::class.simpleName}: ${t.message}; HTTP=${r?.code}"
                    // Complete first: the dialing coroutine is waiting on these
                    // even for a socket that is already stale. A token the daemon
                    // refuses cannot be fixed by trying again; anything else (a
                    // dropped network, a moved daemon) is worth another dial.
                    outcome.complete(
                        if (r?.code == 401) DialOutcome.TOKEN_REJECTED else DialOutcome.RETRY
                    )
                    ended.complete(false)
                    if (stale(w)) return
                    ws = null
                }

                override fun onClosed(w: WebSocket, code: Int, reason: String) {
                    // Nothing is decided here: dial() reads ended == false and
                    // reconnects while the window is still open, or stops because
                    // close() set closing. Setting ENDED here is what used to leave
                    // a backgrounded app stuck on "the connection closed".
                    _detail.value = "onClosed: code=$code reason=$reason"
                    outcome.complete(DialOutcome.RETRY)
                    ended.complete(false)
                    if (stale(w)) return
                    ws = null
                }

                override fun onClosing(w: WebSocket, code: Int, reason: String) {
                    w.close(code, reason)
                }
            })

            when (outcome.await()) {
                DialOutcome.OPEN -> {
                    // Up. Wait for it to go away before deciding what comes next.
                    val shellEnded = ended.await()
                    if (closing) return
                    if (shellEnded) {
                        // Exit/error frame already set the header; the restart
                        // button is how the user asks for another shell.
                        return
                    }
                    // The link dropped under a window that is still open — the app
                    // was backgrounded, the daemon moved, Wi-Fi changed. Reconnect
                    // instead of leaving the panel on a dead connection. This is
                    // what makes returning to the app work.
                    backoff = RETRY_MS
                    _state.value = TermState.CONNECTING
                    _error.value = 0
                    delay(RETRY_MS)
                }

                DialOutcome.TOKEN_REJECTED -> {
                    if (closing) return
                    _state.value = TermState.ENDED
                    _error.value = R.string.offline_token_rejected
                    _detail.value = "token rejected (401)"
                    return
                }

                DialOutcome.RETRY -> {
                    if (closing) return
                    // Keep retrying while the window wants a shell: the daemon may
                    // be mid-restart and its address may be about to change. There
                    // is no give-up here — calling it quits after a fixed number of
                    // tries is what left the terminal on "no daemon address" while
                    // the chat, which waits the same way, was already connected.
                    delay(backoff)
                    backoff = minOf(backoff * 2, 10_000L)
                }
            }
        }
    }

    companion object {
        /**
         * The terminal lives on the same tunnel as the chat: `wss://host/ws` ->
         * `wss://host/terminal`. Only the path differs, so the address discovery
         * already keeps current is the address this uses.
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
        "ready" -> TermEvent.Ready(f.shell.orEmpty(), f.cwd.orEmpty(), f.cols ?: 0, f.rows ?: 0)
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
)

/** `{"type":"resize","cols":100,"rows":30}` — both sizes are always sent. */
internal data class ResizeFrame(
    val type: String,
    val cols: Int,
    val rows: Int,
)
