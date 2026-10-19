package com.tulipskun.aixodia.data.remote

import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.tulipskun.aixodia.R
import com.tulipskun.aixodia.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

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

    /** The shell ended on its own, with this exit code. The stream closes after. */
    data class Exit(val code: Int) : TermEvent

    /** Something failed after the handshake; the stream closes after this too. */
    data class Failed(val message: String) : TermEvent
}

/**
 * One terminal, carried as a stream inside the app's single relay socket.
 *
 * It used to be its own WebSocket to `/terminal`. That needed a second
 * handshake and a second reachable path, and it was the first thing to break
 * when the engine moved behind the relay. Now the stream is opened with a
 * control frame and addressed by id, so it lives and dies with the terminal
 * window while the socket underneath stays up.
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

    /** A string resource id for why the terminal is not up; 0 when nothing is wrong. */
    private val _error = MutableStateFlow(0)
    val error: StateFlow<Int> = _error

    // A channel, not a StateFlow: terminal output is a stream, and a dropped
    // frame would leave the screen showing something the shell never printed.
    private val _output = Channel<ByteArray>(Channel.UNLIMITED)
    val output: Flow<ByteArray> = _output.receiveAsFlow()

    private val _events = Channel<TermEvent>(Channel.UNLIMITED)
    val events: Flow<TermEvent> = _events.receiveAsFlow()

    @Volatile
    private var sid: Int? = null

    // Visible across threads: the socket callback and the UI both decide what a
    // close means.
    @Volatile
    private var closing = false

    /** The last size the page asked for, sent when the stream opens. */
    @Volatile
    private var pendingSize: Pair<Int, Int>? = null

    /** Opens the stream, if it is not already dialing or up. */
    fun open() {
        if (sid != null) return
        closing = false
        _error.value = 0
        _state.value = TermState.CONNECTING
        scope.launch { dial() }
    }

    /**
     * Ends the current shell and opens a new one. Used after an exit or an
     * error, where only a fresh stream can give back a prompt.
     */
    fun restart() {
        close()
        open()
    }

    /** Hangs up. The engine ends that shell; nothing is left running. */
    fun close() {
        closing = true
        sid?.let { relay?.closeTerminal(it) }
        sid = null
        _state.value = TermState.CLOSED
    }

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
        pendingSize = cols to rows
        val id = sid ?: return false
        return relay?.termJSON(id, resizeFrame(cols, rows)) == true
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
        if (c.wsUrl.isBlank() || c.token.isBlank()) {
            _error.value = R.string.terminal_no_address
            _state.value = TermState.CLOSED
            return
        }
        val r = relay
        if (r == null) {
            _error.value = R.string.terminal_no_address
            _state.value = TermState.CLOSED
            return
        }
        r.connect()
        val online = withTimeoutOrNull(15_000) {
            r.state.first { it == ConnState.ONLINE }
        } != null
        if (!online) {
            _error.value = R.string.terminal_closed
            _state.value = TermState.ENDED
            return
        }
        val (cols, rows) = pendingSize ?: (80 to 24)
        val id = "t-" + UUID.randomUUID()
        val opened = r.openTerminal(id, cols, rows, object : TermStream {
            override fun onText(json: String) {
                when (val ev = parseEvent(json)) {
                    is TermEvent.Exit -> {
                        _state.value = TermState.ENDED
                        _events.trySend(ev)
                    }

                    is TermEvent.Failed -> {
                        _state.value = TermState.ENDED
                        _events.trySend(ev)
                    }

                    is TermEvent.Ready -> {
                        _state.value = TermState.OPEN
                        _events.trySend(ev)
                    }

                    null -> Log.w(TAG, "unreadable control frame: ${json.take(120)}")
                }
            }

            override fun onBytes(bytes: ByteArray) {
                _output.trySend(bytes)
            }

            override fun onClosed() {
                sid = null
                if (closing) return
                _state.value = TermState.ENDED
                _error.value = R.string.terminal_closed
            }
        })
        if (opened == null) {
            _error.value = R.string.terminal_closed
            _state.value = TermState.ENDED
            return
        }
        sid = opened
    }

    companion object {
        /**
         * The direct-terminal address of the pre-relay engine. Kept because the
         * mapping is still the documented one for local runs of the engine; the
         * app itself no longer dials it.
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
