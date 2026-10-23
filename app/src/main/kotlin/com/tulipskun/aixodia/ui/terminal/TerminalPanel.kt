package com.tulipskun.aixodia.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tulipskun.aixodia.R
import com.tulipskun.aixodia.data.remote.TermEvent
import com.tulipskun.aixodia.data.remote.TermState
import com.tulipskun.aixodia.data.remote.TerminalSocket
import com.tulipskun.aixodia.term.TerminalEmulator
import com.tulipskun.aixodia.term.TerminalLiveService
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
private val InkRaised = Color(0xFF111821)
private val InkLine = Color(0xFF1A222C)
private val InkActive = Color(0xFF1E2A38)
private val InkText = Color(0xFFD7E0EA)
private val InkDim = Color(0xFF8CA0B3)
private val Signal = Color(0xFF6FE3B0)
private val Danger = Color(0xFFFF8A8A)

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
    val selecting = remember { mutableStateOf(false) }
    val verticalDrag = remember { mutableStateOf(false) }

    val emulator = remember {
        val out = object : TerminalOutput {
            // Anything the emulator owes the shell — a device-status reply, a
            // mouse report — goes back down the same socket.
            override fun write(bytes: ByteArray) {
                socket.send(bytes)
            }

            override fun onScreenChanged() {
                // write() runs on the main thread (the panel switches there
                // before feeding the emulator), so invalidating directly saves
                // a message-loop hop on every batch of output.
                terminalView.value?.onContentChanged()
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
        // The process and this native socket have to stay alive while the
        // screen is open, including with the display folded or the app behind
        // the keyboard. The shell itself lives on the engine either way.
        TerminalLiveService.start(context)
        socket.open()
        onDispose {
            // The shell outlives this window; its modes must outlive it too.
            socket.rememberModes(socket.activeSessionId.value, emulator.snapshotModes())
            socket.close()
            TerminalLiveService.stop(context)
        }
    }

    // A socket Android froze in the background can look OPEN while every byte
    // after the freeze is gone. A long absence rebuilds it; a quick glance at a
    // notification leaves the screen alone. The engine's replay (RIS, modes,
    // ring) is exactly the recovery a fresh attach gives.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var backgroundedAt = 0L
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> backgroundedAt = SystemClock.elapsedRealtime()
                Lifecycle.Event.ON_RESUME -> {
                    val away = if (backgroundedAt > 0L) {
                        SystemClock.elapsedRealtime() - backgroundedAt
                    } else {
                        0L
                    }
                    backgroundedAt = 0L
                    socket.onForeground(away)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        socket.events.collect { ev ->
            when (ev) {
                is TermEvent.Ready -> {
                    ready = ev
                    ended = null
                    // A new stream starts on a fresh page. Repaint the whole
                    // grid as soon as it is up instead of waiting for the first
                    // byte that happens to change a cell; a reconnect's replay
                    // can otherwise leave the old frame visible below it.
                    terminalView.value?.post { terminalView.value?.redrawAll() }
                }

                else -> ended = ev
            }
        }
    }

    // Shells can be killed (or opened from another phone) while this window is
    // up. Re-read the engine's live list periodically so a dead tab disappears
    // and a freed/replaced id is never offered for reattach.
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            socket.refreshSessions()
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

    val currentOnClose by rememberUpdatedState(onClose)
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()

    // Back closes the drawer before it closes the terminal window. Compose's
    // BackHandler callbacks run newest-first, so this one is layered above the
    // chat screen's "leave terminal" handler.
    BackHandler(
        enabled = drawerState.isOpen || drawerState.targetValue != DrawerValue.Closed,
    ) {
        drawerScope.launch { drawerState.close() }
    }

    val restart = {
        ended = null
        ready = null
        emulator.hardReset()
        terminalView.value?.scrollToBottom()
        terminalView.value?.keepSoftKeyboard()
        socket.newSession()
    }
    val closeSession = {
        ended = null
        ready = null
        emulator.hardReset()
        terminalView.value?.scrollToBottom()
        terminalView.value?.keepSoftKeyboard()
        socket.closeSession()
    }
    val status = statusLine(state, errorRes, ready, ended) +
        (if (detail.isNotBlank() && state != TermState.OPEN) "  ·  $detail" else "")

    // The terminal itself is still one platform ViewGroup, but the chrome no
    // longer sits above it: the session controls live in a drawer so the shell
    // gets the whole page. A menu key or a left-to-right swipe in the grid
    // opens it.
    ModalNavigationDrawer(
        drawerState = drawerState,
        // The drawer itself owns the horizontal drag, exactly as it does on the
        // chat screen: the menu follows the finger and can be dragged back.
        // Once the terminal has locked a gesture to the vertical axis, though,
        // the drawer is taken out of that gesture completely; a sideways drift
        // halfway through a scroll must not turn it into a menu swipe. While
        // text is selected it also steps aside so selection drags stay local.
        gesturesEnabled = drawerState.isOpen ||
            (!selecting.value && !verticalDrag.value),
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Ink,
                drawerContentColor = InkText,
            ) {
                TerminalSidebar(
                    socket = socket,
                    status = status,
                    state = state,
                    onRestart = {
                        drawerScope.launch { drawerState.close() }
                        restart()
                    },
                    onCloseSession = {
                        drawerScope.launch { drawerState.close() }
                        closeSession()
                    },
                    onClose = {
                        drawerScope.launch { drawerState.close() }
                        currentOnClose()
                    },
                    onPicked = { drawerScope.launch { drawerState.close() } },
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize().background(Ink)) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    // The system bars set the floor and the ceiling. The nav bar
                    // keeps its strip or it covers the composer.
                    .windowInsetsPadding(WindowInsets.safeDrawing),
                factory = { ctx ->
                    val root = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setBackgroundColor(Ink.toArgb())
                    }

                    // The terminal takes every pixel above the keys, so the grid
                    // always matches the visible area. Session controls live in
                    // the drawer instead of eating a header row.
                    val middle = FrameLayout(ctx)
                    val view = TerminalView(ctx).apply {
                        setBackgroundColor(Ink.toArgb())
                        setEmulator(emulator)
                        inputSink = { socket.send(it) }
                        onResize = { cols, rows -> socket.resize(cols, rows) }
                        onScrollChanged = { atBottom.value = it }
                        onSelectionChanged = { selecting.value = it }
                        onVerticalDrag = { verticalDrag.value = it }
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
                                            modifier = Modifier.clickable {
                                                view.scrollToBottom()
                                                view.keepSoftKeyboard()
                                            },
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
                                        onMenu = { drawerScope.launch { drawerState.open() } },
                                        onCtrl = {
                                            val v = !ctrlOn.value
                                            ctrlOn.value = v
                                            view.setLatchedMods(v, altOn.value)
                                            view.keepSoftKeyboard()
                                        },
                                        onAlt = {
                                            val v = !altOn.value
                                            altOn.value = v
                                            view.setLatchedMods(ctrlOn.value, v)
                                            view.keepSoftKeyboard()
                                        },
                                        onKey = { bytes ->
                                            socket.send(bytes)
                                            // Tapping a key must not take the keyboard with it.
                                            view.keepSoftKeyboard()
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
}

@Composable
private fun TerminalSidebar(
    socket: TerminalSocket,
    status: String,
    state: TermState,
    onRestart: () -> Unit,
    onCloseSession: () -> Unit,
    onClose: () -> Unit,
    onPicked: () -> Unit,
) {
    var confirmCloseSession by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxHeight()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = InkRaised,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, InkLine),
                modifier = Modifier.size(46.dp),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Terminal,
                        contentDescription = null,
                        tint = Signal,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.terminal),
                    style = MaterialTheme.typography.titleLarge,
                    color = InkText,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(statusColor(state)),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        statusLabel(state),
                        style = MaterialTheme.typography.labelMedium,
                        color = InkDim,
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Surface(
            color = InkRaised,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, InkLine),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    "CONNECTION",
                    style = MaterialTheme.typography.labelSmall,
                    color = InkDim,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    status.ifBlank { "—" },
                    style = MaterialTheme.typography.bodySmall,
                    color = InkText,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.terminal_sessions),
                    style = MaterialTheme.typography.labelMedium,
                    color = InkDim,
                )
                NewSessionButton(onClick = onRestart)
            }
            Spacer(Modifier.height(10.dp))
            TerminalSessionBar(socket = socket, onPicked = onPicked)
            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = InkLine)
            Spacer(Modifier.height(12.dp))
            Text(
                "ACTIONS",
                style = MaterialTheme.typography.labelSmall,
                color = InkDim,
            )
            Spacer(Modifier.height(4.dp))
            SidebarAction(
                label = stringResource(R.string.terminal_close_session),
                icon = Icons.Default.Delete,
                danger = true,
                onClick = { confirmCloseSession = true },
            )
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = InkLine)
        Spacer(Modifier.height(4.dp))
        SidebarAction(
            label = stringResource(R.string.terminal_close),
            icon = Icons.Default.Close,
            onClick = onClose,
        )
    }

    if (confirmCloseSession) {
        AlertDialog(
            onDismissRequest = { confirmCloseSession = false },
            containerColor = InkRaised,
            titleContentColor = InkText,
            textContentColor = InkDim,
            title = { Text(stringResource(R.string.terminal_close_session_title)) },
            text = { Text(stringResource(R.string.terminal_close_session_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmCloseSession = false
                        onCloseSession()
                    },
                ) {
                    Text(stringResource(R.string.terminal_close_session), color = Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCloseSession = false }) {
                    Text(stringResource(R.string.terminal_cancel), color = InkDim)
                }
            },
        )
    }
}

@Composable
private fun NewSessionButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Surface(
        color = InkActive,
        shape = shape,
        border = BorderStroke(1.dp, InkLine),
        modifier = Modifier
            .clip(shape)
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = Signal,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                stringResource(R.string.terminal_new),
                style = MaterialTheme.typography.labelMedium,
                color = Signal,
            )
        }
    }
}

@Composable
private fun SidebarAction(
    label: String,
    icon: ImageVector,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (danger) Danger else InkDim,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (danger) Danger else InkText,
        )
    }
}

private fun statusColor(state: TermState): Color = when (state) {
    TermState.OPEN -> Signal
    TermState.CONNECTING -> Color(0xFFFFC66D)
    TermState.ENDED, TermState.CLOSED -> Danger
}

@Composable
private fun statusLabel(state: TermState): String = when (state) {
    TermState.OPEN -> stringResource(R.string.online)
    TermState.CONNECTING -> stringResource(R.string.connecting)
    TermState.ENDED, TermState.CLOSED -> stringResource(R.string.offline)
}

/**
 * The keys a phone keyboard does not have. A shell without Ctrl-C cannot stop
 * anything, so these are not extras: they are what makes the terminal usable.
 *
 * Ctrl and Alt latch for one keystroke: tap Ctrl, then type C, and the view
 * turns it into 0x03 (the same as the ^C key, but for any letter or symbol).
 */
@Composable
private fun TerminalKeys(
    ctrlOn: Boolean,
    altOn: Boolean,
    onMenu: () -> Unit,
    onCtrl: () -> Unit,
    onAlt: () -> Unit,
    onKey: (ByteArray) -> Unit,
) {
    // Exactly two rows, five keys each. The order is deliberate: the arrows
    // sit where the thumb expects them, and DelAll is the whole-line erase
    // (Ctrl-U/Ctrl-K) rather than a burst of backspaces.
    val top = listOf(
        "Esc" to byteArrayOf(27),
        "Tab" to byteArrayOf(9),
        "^P" to byteArrayOf(16),
        "\u2191" to "\u001b[A".toByteArray(),
        // Ctrl-U then Ctrl-K: kill back to the start and forward to the end,
        // so the whole line goes even when the cursor was left in the middle.
        "DelAll" to byteArrayOf(21, 11),
    )
    val bottom = listOf(
        "\u2190" to "\u001b[D".toByteArray(),
        "\u2193" to "\u001b[B".toByteArray(),
        "\u2192" to "\u001b[C".toByteArray(),
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Two rows tall, so it is a full-height target instead of another
        // narrow chip squeezed between the keys.
        Surface(
            color = InkLine,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .width(48.dp)
                .height(70.dp)
                .clickable(onClick = onMenu),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Menu,
                    contentDescription = "Menu",
                    tint = InkText,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                top.forEach { (label, bytes) ->
                    Box(Modifier.weight(1f)) {
                        KeyChip(label, wide = true, onClick = { onKey(bytes) })
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    KeyChip("Ctrl", active = ctrlOn, wide = true, onClick = onCtrl)
                }
                Box(Modifier.weight(1f)) {
                    KeyChip("Alt", active = altOn, wide = true, onClick = onAlt)
                }
                bottom.forEach { (label, bytes) ->
                    Box(Modifier.weight(1f)) {
                        KeyChip(label, wide = true, onClick = { onKey(bytes) })
                    }
                }
            }
        }
    }
}

/** One tappable key; [active] fills it in while a modifier is latched. */
@Composable
private fun KeyChip(
    label: String,
    active: Boolean = false,
    wide: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        color = if (active) Color(0xFF2A3A4A) else InkLine,
        shape = RoundedCornerShape(8.dp),
        modifier = (if (wide) Modifier.fillMaxWidth() else Modifier)
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .then(if (wide) Modifier.fillMaxWidth() else Modifier)
                .defaultMinSize(minWidth = 44.dp, minHeight = 32.dp)
                .padding(horizontal = if (wide) 4.dp else 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (active) Color(0xFFE8F0F8) else InkText,
                maxLines = 1,
                softWrap = false,
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

@Composable
private fun TerminalSessionBar(socket: TerminalSocket, onPicked: () -> Unit = {}) {
    val sessions by socket.sessions.collectAsStateWithLifecycle()
    val pids by socket.sessionPids.collectAsStateWithLifecycle()
    val active by socket.activeSessionId.collectAsStateWithLifecycle()

    if (sessions.isEmpty()) {
        Surface(
            color = InkRaised,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, InkLine),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.terminal_no_sessions),
                style = MaterialTheme.typography.bodySmall,
                color = InkDim,
                modifier = Modifier.padding(14.dp),
            )
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        sessions.forEachIndexed { i, id ->
            // The pid is what makes a tab a shell and not a position: closing
            // Shell 2 used to renumber Shell 3, so "Shell 1" no longer told
            // you which process a reconnect would land on. The number is for
            // quick tapping; the pid identifies the process behind it.
            val pid = pids[id]
            // Only the session that is actually attached is selected. Falling
            // back to "the last tab" when nothing is attached marked a closed
            // tab as the live one.
            val selected = active.isNotBlank() && id == active
            val shape = RoundedCornerShape(14.dp)
            Surface(
                color = if (selected) InkActive else InkRaised,
                shape = shape,
                border = BorderStroke(
                    1.dp,
                    if (selected) Color(0xFF31516B) else InkLine,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .clickable {
                        socket.switchSession(id)
                        onPicked()
                    },
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (selected) Signal else InkDim.copy(alpha = 0.35f)),
                    )
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Shell ${i + 1}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = InkText,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (pid != null && pid > 0) {
                                stringResource(R.string.terminal_session_pid, pid)
                            } else {
                                stringResource(R.string.terminal_session_starting)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = InkDim,
                        )
                    }
                    if (selected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = stringResource(R.string.terminal_active),
                            tint = Signal,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
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
