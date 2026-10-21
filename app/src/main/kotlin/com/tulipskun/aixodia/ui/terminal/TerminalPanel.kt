package com.tulipskun.aixodia.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tulipskun.aixodia.R
import com.tulipskun.aixodia.data.remote.TermEvent
import com.tulipskun.aixodia.data.remote.TermState
import com.tulipskun.aixodia.data.remote.TerminalSocket
import com.tulipskun.aixodia.term.TerminalEmulator
import com.tulipskun.aixodia.term.TerminalOutput
import com.tulipskun.aixodia.term.TerminalView
import com.tulipskun.aixodia.ui.theme.AIxodiaTheme
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The panel's own colours: a terminal reads as a terminal, not as a chat bubble. */
private val Ink = Color(0xFF0B0F14)
private val InkLine = Color(0xFF1A222C)
private val InkText = Color(0xFFD7E0EA)
private val InkDim = Color(0xFF8CA0B3)

/**
 * A terminal on the machine the engine runs on, sliding in over the chat.
 *
 * The rendering and the emulation are the app's own — a native [TerminalView]
 * drawing a [TerminalEmulator] — so a finger is a mouse click, a key is a byte,
 * and nothing stands between the socket and the screen. The window owns the
 * connection: it dials when it appears and hangs up when it goes away.
 *
 * The shell is the engine's, on the engine's host — this is the phone looking at
 * that machine, not a shell on the phone.
 */
@Composable
fun TerminalWindow(socket: TerminalSocket, onClose: () -> Unit) {
    val state by socket.state.collectAsStateWithLifecycle()
    val errorRes by socket.error.collectAsStateWithLifecycle()
    val detail by socket.detail.collectAsStateWithLifecycle()
    val activeSessionId by socket.activeSessionId.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var ready by remember { mutableStateOf<TermEvent.Ready?>(null) }
    var ended by remember { mutableStateOf<TermEvent?>(null) }

    // The view is created by Compose, the emulator by us; each needs the other,
    // so the view is held in a state the emulator's callbacks read.
    val terminalView = remember { mutableStateOf<TerminalView?>(null) }
    val ctrlOn = remember { mutableStateOf(false) }
    val altOn = remember { mutableStateOf(false) }
    val atBottom = remember { mutableStateOf(true) }

    val emulator = remember {
        val out = object : TerminalOutput {
            // Anything the emulator owes the shell — a device-status reply, a
            // mouse report — goes back down the same socket.
            override fun write(bytes: ByteArray) {
                socket.send(bytes)
            }

            override fun onScreenChanged() {
                val v = terminalView.value ?: return
                v.post { v.onContentChanged() }
            }

            override fun onTitleChanged(title: String) = Unit

            override fun onClipboard(text: String) {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return
                cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
            }
        }
        // A placeholder size until the view is measured and reports the real one.
        val em = TerminalEmulator(80, 24, out)
        // Reattaching to a session that is already running: put its modes back
        // before the engine's replay lands, so a full-screen program keeps its
        // alternate screen and a swipe stays a wheel.
        socket.rememberedModes(socket.activeSessionId.value)?.let(em::applyModes)
        em
    }

    DisposableEffect(Unit) {
        socket.open()
        onDispose {
            // The shell outlives this window; its modes must outlive it too.
            socket.rememberModes(socket.activeSessionId.value, emulator.snapshotModes())
            socket.close()
        }
    }

    LaunchedEffect(Unit) {
        socket.events.collect { ev ->
            when (ev) {
                is TermEvent.Ready -> {
                    ready = ev
                    ended = null
                }

                else -> ended = ev
            }
        }
    }

    // Output is raw bytes and can arrive in many small writes per keystroke.
    // They are gathered for a few milliseconds and written once, on the main
    // thread — the emulator and the view both belong to it.
    LaunchedEffect(Unit) {
        val pending = ByteArrayOutputStream(8 * 1024)
        val lock = Any()
        val arrived = Channel<Unit>(Channel.CONFLATED)
        val pump = launch {
            socket.output.collect { bytes ->
                synchronized(lock) { pending.write(bytes) }
                arrived.trySend(Unit)
            }
        }
        try {
            while (true) {
                arrived.receive()
                delay(4)
                val batch = synchronized(lock) {
                    val b = pending.toByteArray()
                    pending.reset()
                    b
                }
                if (batch.isEmpty()) continue
                withContext(Dispatchers.Main) { emulator.write(batch) }
            }
        } finally {
            pump.cancel()
        }
    }

    // Remember the modes of the screen being left, and forget the previous run's
    // exit banner. The new screen itself is cleared by the engine: every attach
    // starts with RIS and the session's own modes, so resetting the emulator
    // here would race the replay — when the replay arrived first, this reset ran
    // second and wiped the screen it had just drawn.
    val lastSession = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(activeSessionId) {
        if (lastSession.value != null && activeSessionId != lastSession.value) {
            lastSession.value?.let { socket.rememberModes(it, emulator.snapshotModes()) }
            ended = null
            ready = null
        }
        if (activeSessionId.isNotBlank()) lastSession.value = activeSessionId
    }

    // The whole screen lives inside one platform ViewGroup. The terminal is a
    // plain child View; the header, the session tabs, the keys and the composer
    // are Compose hosted above and below it in the same ViewGroup. The ViewGroup
    // — not Compose — owns the placement, so the terminal can never be painted
    // over the header, and no Compose layer can separate the driven view from
    // the chrome that drives it.
    val currentOnClose by rememberUpdatedState(onClose)

    Box(Modifier.fillMaxSize().background(Ink)) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                // The system bars set the floor and the ceiling. The nav bar
                // keeps its strip or it covers the composer.
                .windowInsetsPadding(WindowInsets.safeDrawing),
            factory = { ctx ->
                val restart = {
                    ended = null
                    ready = null
                    emulator.hardReset()
                    terminalView.value?.scrollToBottom()
                    terminalView.value?.requestFocus()
                    socket.newSession()
                }
                val closeSession = {
                    ended = null
                    ready = null
                    emulator.hardReset()
                    terminalView.value?.scrollToBottom()
                    terminalView.value?.requestFocus()
                    socket.closeSession()
                }

                val root = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Ink.toArgb())
                }

                // The header and the session tabs stay put even with the
                // keyboard up: closing a shell is not something the keyboard
                // should take away. The terminal gives up the room instead.
                val header = ComposeView(ctx).apply {
                    setContent {
                        AIxodiaTheme {
                            Column {
                                TerminalHeader(
                                    status = statusLine(state, errorRes, ready, ended) +
                                        (if (detail.isNotBlank()) "  ·  $detail" else ""),
                                    canRestart = state == TermState.CLOSED || state == TermState.ENDED,
                                    onRestart = restart,
                                    onCloseSession = closeSession,
                                    onClose = { currentOnClose() },
                                )
                                TerminalSessionBar(socket = socket)
                            }
                        }
                    }
                }
                root.addView(
                    header,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )

                // The terminal sits between the chrome and takes every pixel
                // left over, so the grid always matches the visible area.
                val middle = FrameLayout(ctx)
                val view = TerminalView(ctx).apply {
                    setBackgroundColor(Ink.toArgb())
                    setEmulator(emulator)
                    inputSink = { socket.send(it) }
                    onResize = { cols, rows -> socket.resize(cols, rows) }
                    onScrollChanged = { atBottom.value = it }
                    onModsConsumed = {
                        // The view has spent its latch; keep the chips in step.
                        ctrlOn.value = false
                        altOn.value = false
                    }
                    requestFocus()
                    terminalView.value = this
                }
                middle.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )

                // Only while there is history above: a terminal at the prompt
                // needs no button. It is a sibling of the terminal inside the
                // same FrameLayout, so it rides above it without Compose layers.
                val scrollDown = ComposeView(ctx).apply {
                    setContent {
                        AIxodiaTheme {
                            if (!atBottom.value) {
                                Box(Modifier.padding(end = 12.dp, bottom = 12.dp)) {
                                    Surface(
                                        color = Color(0xFF1A222C),
                                        shape = RoundedCornerShape(50),
                                        modifier = Modifier.clickable { view.scrollToBottom() },
                                    ) {
                                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Default.KeyboardArrowDown,
                                                contentDescription = "Scroll to bottom",
                                                tint = InkText,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                middle.addView(
                    scrollDown,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM or Gravity.END,
                    ),
                )

                root.addView(
                    middle,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f,
                    ),
                )

                // The keys and the composer are the floor. They stay under the
                // thumb and out of the terminal's way, and the terminal scrolls
                // the whole grid instead of pushing the composer around.
                val footer = ComposeView(ctx).apply {
                    setContent {
                        AIxodiaTheme {
                            Column {
                                TerminalKeys(
                                    ctrlOn = ctrlOn.value,
                                    altOn = altOn.value,
                                    onCtrl = {
                                        val v = !ctrlOn.value
                                        ctrlOn.value = v
                                        view.setLatchedMods(v, altOn.value)
                                        view.requestFocus()
                                    },
                                    onAlt = {
                                        val v = !altOn.value
                                        altOn.value = v
                                        view.setLatchedMods(ctrlOn.value, v)
                                        view.requestFocus()
                                    },
                                    onKey = { bytes ->
                                        socket.send(bytes)
                                        // Tapping a key must not take the keyboard with it.
                                        view.requestFocus()
                                    },
                                )
                                TerminalComposer(
                                    socket = socket,
                                    onSend = {
                                        if (ctrlOn.value || altOn.value) {
                                            ctrlOn.value = false
                                            altOn.value = false
                                            view.setLatchedMods(false, false)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                root.addView(
                    footer,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )

                root
            },
        )

        LaunchedEffect(terminalView.value) {
            terminalView.value?.requestFocus()
        }
    }
}

@Composable
private fun TerminalHeader(
    status: String,
    canRestart: Boolean,
    onRestart: () -> Unit,
    onCloseSession: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 2.dp, top = 4.dp, bottom = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.terminal),
                style = MaterialTheme.typography.titleSmall,
                color = InkText,
            )
            if (status.isNotBlank()) {
                Text(
                    status,
                    style = MaterialTheme.typography.labelSmall,
                    color = InkDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (canRestart) {
            IconButton(onClick = onRestart, modifier = Modifier.size(38.dp)) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.terminal_open_new),
                    tint = InkDim,
                )
            }
        }
        IconButton(onClick = onCloseSession, modifier = Modifier.size(38.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Close session",
                tint = InkDim,
            )
        }
        IconButton(onClick = onClose, modifier = Modifier.size(38.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.terminal_close),
                tint = InkDim,
            )
        }
    }
}

/**
 * The keys a phone keyboard does not have. A shell without Ctrl-C cannot stop
 * anything, so these are not extras: they are what makes the terminal usable.
 *
 * Ctrl and Alt latch for one keystroke: tap Ctrl, then type C, and the view
 * turns it into 0x03 (the same as the ^C key, but for any letter or symbol).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TerminalKeys(
    ctrlOn: Boolean,
    altOn: Boolean,
    onCtrl: () -> Unit,
    onAlt: () -> Unit,
    onKey: (ByteArray) -> Unit,
) {
    val keys = listOf(
        "^C" to byteArrayOf(3),
        "^D" to byteArrayOf(4),
        "^Z" to byteArrayOf(26),
        "^L" to byteArrayOf(12),
        "Tab" to byteArrayOf(9),
        "Esc" to byteArrayOf(27),
        "\u2190" to "\u001b[D".toByteArray(),
        "\u2191" to "\u001b[A".toByteArray(),
        "\u2193" to "\u001b[B".toByteArray(),
        "\u2192" to "\u001b[C".toByteArray(),
        "PgUp" to "\u001b[5~".toByteArray(),
        "PgDn" to "\u001b[6~".toByteArray(),
    )
    // The keys wrap instead of scrolling sideways: a row that has to be dragged
    // left and right hides the key you want behind the edge of the screen.
    FlowRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        KeyChip("Ctrl", active = ctrlOn, onClick = onCtrl)
        KeyChip("Alt", active = altOn, onClick = onAlt)
        keys.forEach { (label, bytes) ->
            KeyChip(label, onClick = { onKey(bytes) })
        }
    }
}

/** One tappable key; [active] fills it in while a modifier is latched. */
@Composable
private fun KeyChip(label: String, active: Boolean = false, onClick: () -> Unit) {
    Surface(
        color = if (active) Color(0xFF2A3A4A) else InkLine,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .defaultMinSize(minWidth = 44.dp, minHeight = 32.dp)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (active) Color(0xFFE8F0F8) else InkText,
            )
        }
    }
}

/**
 * A line the phone keyboard can type into. Enter is the ordinary return key, not
 * an IME "send" key: the newline that ends a line belongs to the shell.
 */
@Composable
private fun TerminalComposer(socket: TerminalSocket, onSend: () -> Unit = {}) {
    var text by remember { mutableStateOf("") }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { value ->
                // A newline means the line is finished: send every completed line
                // and keep only what is still being typed. An empty line is not
                // dropped — pressing return on a bare prompt sends the newline
                // the shell is waiting for.
                var rest = value
                var nl = rest.indexOf('\n')
                while (nl >= 0) {
                    val line = rest.substring(0, nl)
                    // CR, not LF: this is the same byte the terminal's Enter
                    // sends, so an empty line submits exactly like a real one.
                    socket.send((line + "\r").toByteArray())
                    onSend()
                    rest = rest.substring(nl + 1)
                    nl = rest.indexOf('\n')
                }
                text = rest
            },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Type a command…", color = InkDim) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = InkText),
            maxLines = 3,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Default),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TerminalSessionBar(socket: TerminalSocket) {
    val sessions by socket.sessions.collectAsStateWithLifecycle()
    val pids by socket.sessionPids.collectAsStateWithLifecycle()
    val active by socket.activeSessionId.collectAsStateWithLifecycle()
    FlowRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        sessions.forEachIndexed { i, id ->
            // The pid is what makes a tab a shell and not a position: closing
            // Shell 2 used to renumber Shell 3, so "Shell 1" no longer told
            // you which process a reconnect would land on. Both are shown —
            // the number for quick tapping, the pid for identity.
            val pid = pids[id]
            val label = if (pid != null && pid > 0) "Shell ${i + 1} · pid $pid" else "Shell ${i + 1}"
            // Only the session that is actually attached is selected. Falling
            // back to "the last tab" when nothing is attached marked a closed
            // tab as the live one.
            val selected = active.isNotBlank() && id == active
            Surface(
                color = if (selected) Color(0xFF2A3A4A) else InkLine,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.clickable { socket.switchSession(id) },
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) Color(0xFFE8F0F8) else InkText,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        Surface(
            color = InkLine,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.clickable { socket.newSession() },
        ) {
            Text(
                "+",
                style = MaterialTheme.typography.labelMedium,
                color = InkText,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

/** One line under the title: what the shell is, or how it ended. */
@Composable
private fun statusLine(
    state: TermState,
    errorRes: Int,
    ready: TermEvent.Ready?,
    ended: TermEvent?,
): String = when {
    ended is TermEvent.Exit -> stringResource(R.string.terminal_ended, ended.code)
    ended is TermEvent.Failed -> stringResource(R.string.terminal_failed, ended.message)
    state == TermState.OPEN -> listOfNotNull(
        ready?.shell,
        ready?.cwd,
        ready?.pid?.takeIf { it > 0 }?.let { "pid $it" },
    )
        .filter { it.isNotBlank() }
        .joinToString("  ")
    state == TermState.CONNECTING -> stringResource(R.string.terminal_connecting)
    errorRes != 0 -> stringResource(errorRes)
    state == TermState.ENDED -> stringResource(R.string.terminal_closed)
    else -> ""
}
