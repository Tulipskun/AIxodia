package com.tulipskun.aixodia.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import com.tulipskun.aixodia.term.TerminalConnections
import com.tulipskun.aixodia.term.TerminalEmulator
import com.tulipskun.aixodia.term.TerminalLiveService
import com.tulipskun.aixodia.term.TerminalOutput
import com.tulipskun.aixodia.term.TerminalView
import com.tulipskun.aixodia.ui.theme.AIxodiaTheme
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
 * and nothing stands between the socket and the screen. Every tab owns its own
 * connection and emulator: switching tabs never redials, and all of them hang
 * up together when the window goes away.
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
    val sessions by socket.sessions.collectAsStateWithLifecycle()
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

    // One emulator per tab, not one for the window. A tab keeps receiving its
    // shell's bytes while another tab is on screen, so switching only swaps
    // which grid the view draws — no redial, no replay, no blank page.
    val emulators = remember { mutableMapOf<String, TerminalEmulator>() }
    val activeEmulator = remember { mutableStateOf<TerminalEmulator?>(null) }
    // Every tab's grid matches the view, not just the visible one. An inactive
    // full-screen program keeps painting while it is hidden, and those bytes
    // must land on a grid of the same size as the one it will be shown on.
    val gridSize = remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val output = remember {
        object : TerminalOutput {
            // Anything an emulator owes the shell — a device-status reply, a
            // mouse report — goes back down the tab that is on screen.
            override fun write(bytes: ByteArray) {
                socket.send(bytes)
            }

            override fun onScreenChanged() {
                // write() runs on the main thread (the panel switches there
                // before feeding an emulator), so invalidating directly saves
                // a message-loop hop on every batch of output. An inactive tab
                // repaints the same view; it is just a cheap invalidate.
                terminalView.value?.onContentChanged()
            }

            override fun onTitleChanged(title: String) = Unit

            override fun onClipboard(text: String) {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return
                cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
            }
        }
    }
    // A placeholder size until the view is measured and reports the real one.
    val placeholder = remember { TerminalEmulator(80, 24, output) }

    fun emulatorFor(id: String): TerminalEmulator = emulators.getOrPut(id) {
        if (id.isBlank()) {
            placeholder
        } else {
            TerminalEmulator(80, 24, output).also { em ->
                // Reattaching to a session that is already running: put its
                // modes back before the engine's replay lands, so a full-screen
                // program keeps its alternate screen and a swipe stays a wheel.
                socket.rememberedModes(id)?.let(em::applyModes)
                gridSize.value?.let { (cols, rows) -> em.resize(cols, rows) }
            }
        }
    }

    DisposableEffect(Unit) {
        // The process and every tab's socket have to stay alive while the
        // screen is open, including after switching to another app. The shell
        // itself lives on the engine either way.
        TerminalConnections.attach(socket)
        TerminalLiveService.start(context)
        socket.open()
        onDispose {
            // The shell outlives this window; its modes must outlive it too.
            val id = socket.activeSessionId.value
            if (id.isNotBlank()) {
                emulators[id]?.let { socket.rememberModes(id, it.snapshotModes()) }
            }
            // Keep the socket and foreground service alive across navigation
            // (Terminal -> Chat -> Terminal) so tabs do not have to reconnect.
            // Teardown happens on explicit tab close / app removal instead.
        }
    }

    // Backgrounding is intentionally cheap: the foreground service keeps the
    // process and every WebSocket alive, so START does not redial. It only
    // stops the tab-list poll while the window is not visible and asks for one
    // fresh list when the user comes back.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    // If the system stopped the service while the task was
                    // still around, coming back restores the foreground
                    // protection before any socket is touched.
                    TerminalLiveService.start(context)
                    socket.setAppVisible(true)
                }
                Lifecycle.Event.ON_STOP -> socket.setAppVisible(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        socket.events.collect { tabEvent ->
            // Inactive tabs keep running, but their exit banners belong to
            // those tabs. Only the visible one drives this screen.
            if (tabEvent.sessionId != socket.activeSessionId.value) return@collect
            when (val ev = tabEvent.event) {
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

    // Every open tab drains its own output. A tab that is not on screen keeps
    // its emulator current, so switching to it shows the live screen at once
    // instead of reattaching and replaying. Output is raw bytes and can arrive
    // in many small writes per keystroke, so each tab batches for a few
    // milliseconds and writes on the main thread, where its emulator lives.
    LaunchedEffect(sessions) {
        sessions.forEach { id ->
            launch {
                var output = socket.outputFor(id)
                // A tab is listed from the saved state before its link starts.
                // Wait for the link rather than silently dropping its replay.
                while (output == null) {
                    delay(50)
                    output = socket.outputFor(id)
                }
                // Let the view report its real grid before building a new
                // emulator, so the engine's first replay is laid out at the
                // size it will actually be drawn at. A view that never measures
                // still gets the placeholder after a second.
                var waitedForSize = 0L
                while (gridSize.value == null && waitedForSize < 1_000L) {
                    delay(16)
                    waitedForSize += 16
                }
                pumpTabOutput(output, emulatorFor(id))
            }
        }
    }

    // Bind the visible tab. Switching is only an emulator swap: every tab's
    // socket and grid stayed alive while it was in the background.
    LaunchedEffect(activeSessionId) {
        val id = activeSessionId
        ready = if (id.isBlank()) null else socket.tabReady(id)
        ended = if (id.isBlank()) {
            null
        } else {
            socket.lastEvent(id)?.takeIf { it !is TermEvent.Ready }
        }
        val em = if (id.isBlank()) placeholder else emulatorFor(id)
        activeEmulator.value = em
        terminalView.value?.setEmulator(em)
        terminalView.value?.scrollToBottom()
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
        terminalView.value?.scrollToBottom()
        terminalView.value?.keepSoftKeyboard()
        socket.newSession()
    }
    val closeSession = {
        ended = null
        ready = null
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
                        setEmulator(activeEmulator.value ?: placeholder)
                        inputSink = { socket.send(it) }
                        onResize = { cols, rows ->
                            gridSize.value = cols to rows
                            socket.resize(cols, rows)
                            emulators.values.toList().forEach { em ->
                                if (em.columns != cols || em.rows != rows) em.resize(cols, rows)
                            }
                        }
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

/**
 * Feeds one tab's bytes into its emulator without making the shell wait for a
 * frame. Every tab has its own pump, so a busy background tab cannot add
 * latency to the one on screen.
 */
private suspend fun pumpTabOutput(
    output: Flow<ByteArray>,
    emulator: TerminalEmulator,
) = coroutineScope {
    val pending = ByteArrayOutputStream(8 * 1024)
    val lock = Any()
    val arrived = Channel<Unit>(Channel.CONFLATED)
    val pump = launch {
        output.collect { bytes ->
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
    // Exactly two rows, six keys each. The order is deliberate: the arrows
    // sit where the thumb expects them, and DelAll is the whole-line erase
    // (Ctrl-U/Ctrl-K) rather than a burst of backspaces. CMD is the same byte
    // the old "^P" sent; only its label changed. SHELL replaces the running
    // shell with a fresh login shell in place, which is how a wedged or
    // half-configured session gets a clean one without a new tab.
    val top = listOf(
        "Esc" to byteArrayOf(27),
        "Tab" to byteArrayOf(9),
        "CMD" to byteArrayOf(16),
        "\u2191" to "\u001b[A".toByteArray(),
        // Ctrl-U then Ctrl-K: kill back to the start and forward to the end,
        // so the whole line goes even when the cursor was left in the middle.
        "DelAll" to byteArrayOf(21, 11),
        "SHELL" to "exec \$SHELL\r".toByteArray(),
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
                // No longer a full-height slab: the menu is one key among the
                // others, at the far right of the bottom row where the thumb
                // already is.
                Box(Modifier.weight(1f)) {
                    MenuKey(onClick = onMenu)
                }
            }
        }
    }
}

/** The drawer key, drawn to the same size as a [KeyChip] so the bar stays even. */
@Composable
private fun MenuKey(onClick: () -> Unit) {
    Surface(
        color = InkLine,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minWidth = 44.dp, minHeight = 32.dp)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Menu,
                contentDescription = "Menu",
                tint = InkText,
                modifier = Modifier.size(18.dp),
            )
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
