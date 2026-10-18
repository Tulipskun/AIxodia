package com.tulipskun.aixodia.data.remote

import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.tulipskun.aixodia.R
import com.tulipskun.aixodia.SettingsStore
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
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
        // OkHttp leaves Nagle's algorithm on, which holds a small write until
        // the previous one is acknowledged. A keystroke is exactly that small
        // write, so every key would pay for it. A terminal wants each byte on
        // the wire as soon as it is written.
        .socketFactory(lowDelaySockets)
        .build()

    private val _state = MutableStateFlow(TermState.CLOSED)
    val state: StateFlow<TermState> = _state

    /** A string resource id for why the terminal is not up; 0 when nothing is wrong. */
    private val _error = MutableStateFlow(0)
    val error: StateFlow<Int> = _error

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

    // Touched from the socket's own callback thread as well as the UI's, so the
    // two flags that decide what a close means have to be visible across threads.
    @Volatile
    private var closing = false

    /** The last size the page asked for, resent when the socket comes up. */
    @Volatile
    private var pendingSize: Pair<Int, Int>? = null

    /** Dials the terminal, if it is not already dialing or up. */
    fun open() {
        if (ws != null) return
        closing = false
        _error.value = 0
        _state.value = TermState.CONNECTING
        scope.launch { dial() }
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
        closing = true
        ws?.close(1000, "window closed")
        ws = null
        _state.value = TermState.CLOSED
    }

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
        val c = try {
            settings.current()
        } catch (t: Throwable) {
            Log.w(TAG, "settings unreadable", t)
            _error.value = R.string.terminal_no_address
            _state.value = TermState.CLOSED
            return
        }
        val url = terminalUrlFor(c.wsUrl)
        if (url.isBlank() || c.token.isBlank()) {
            // Same rule as the chat socket: a fresh install has no address yet,
            // and the terminal waits quietly for discovery instead of dialing
            // something that was never configured.
            _error.value = R.string.terminal_no_address
            _state.value = TermState.CLOSED
            return
        }
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${c.token}")
            .build()
        val settled = CompletableDeferred<Unit>()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: Response) {
                _state.value = TermState.OPEN
                // The size the page already reported, now that there is a shell
                // to tell it to.
                pendingSize?.let { (cols, rows) -> w.send(resizeFrame(cols, rows)) }
                settled.complete(Unit)
            }

            override fun onMessage(w: WebSocket, bytes: ByteString) {
                _output.trySend(bytes.toByteArray())
            }

            override fun onMessage(w: WebSocket, text: String) {
                when (val ev = parseEvent(text)) {
                    is TermEvent.Exit -> {
                        _state.value = TermState.ENDED
                        _events.trySend(ev)
                    }

                    is TermEvent.Failed -> {
                        _state.value = TermState.ENDED
                        _events.trySend(ev)
                    }

                    is TermEvent.Ready -> _events.trySend(ev)

                    // An unreadable control frame is not worth ending a working
                    // shell over; the bytes keep flowing either way.
                    null -> Log.w(TAG, "unreadable control frame: ${text.take(120)}")
                }
            }

            override fun onFailure(w: WebSocket, t: Throwable, r: Response?) {
                Log.w(TAG, "terminal socket failure", t)
                settled.complete(Unit)
                ws = null
                if (closing) return
                _state.value = TermState.ENDED
                _error.value = when (r?.code) {
                    401 -> R.string.offline_token_rejected
                    else -> R.string.terminal_closed
                }
            }

            override fun onClosed(w: WebSocket, code: Int, reason: String) {
                settled.complete(Unit)
                ws = null
                if (closing) return
                if (_state.value == TermState.OPEN) {
                    // The engine hung up without an exit frame: the shell is
                    // gone either way, so say so rather than spinners forever.
                    _state.value = TermState.ENDED
                    _error.value = R.string.terminal_closed
                }
            }

            override fun onClosing(w: WebSocket, code: Int, reason: String) {
                w.close(code, reason)
            }
        })
        settled.await()
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

/**
 * OkHttp leaves Nagle's algorithm on, so a small write can sit until the
 * previous one is acknowledged. A terminal frame is small by nature — one
 * keystroke, one byte — and that wait is the difference between typing feeling
 * immediate and feeling lagged. TCP_NODELAY is set on the socket before TLS
 * wraps it, so the option stays on the fd the encrypted stream uses.
 */
private val lowDelaySockets = object : SocketFactory() {
    private val delegate = SocketFactory.getDefault()

    private fun tuned(socket: Socket): Socket = socket.apply { tcpNoDelay = true }

    override fun createSocket(host: String, port: Int): Socket =
        tuned(delegate.createSocket(host, port))

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        tuned(delegate.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: InetAddress, port: Int): Socket =
        tuned(delegate.createSocket(host, port))

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        tuned(delegate.createSocket(address, port, localAddress, localPort))
}

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
