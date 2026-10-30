@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick as semanticsOnClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
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
import com.tulipskun.aixodia.ui.display.drawerFloorGuard
import com.tulipskun.aixodia.ui.theme.AIxodiaTheme
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
 * A held arrow starts repeating after the first pause, then fires once per
 * repeat window. The first pause is the keyboard's: long enough that a quick
 * tap never doubles, short enough that a held arrow feels live.
 */
private const val WRITE_CHUNK = 16 * 1024

private const val KEY_REPEAT_FIRST_MS = 400L
private const val KEY_REPEAT_MS = 100L

/**
 * The parts of the terminal that must outlive the window.
 *
 * Going back to the chat hides the terminal, but its shells and WebSockets stay
 * attached. The grids have to stay too: a new [TerminalEmulator] would be blank
 * until the shell printed again, so returning would look like a reconnect to a
 * fresh page even though the same shell never went away. This holder is created
 * once per screen and handed to every composition of [TerminalWindow].
 */
class TerminalUiState(private val socket: TerminalSocket) {
    /** One emulator per tab, kept across hide/show. Main thread only. */
    val emulators = mutableMapOf<String, TerminalEmulator>()

    /** The view currently drawing (null while the window is hidden). */
    var view: TerminalView? = null

    /** The visible tab's grid when the window was last hidden. */
    var active: TerminalEmulator? = null

    /** The last measured size, so a tab built while hidden already fits. */
    var grid: Pair<Int, Int>? = null

    private val output = object : TerminalOutput {
        // Anything an emulator owes the shell — a device-status reply, a mouse
        // report — goes back down the tab that is on screen.
        override fun write(bytes: ByteArray) {
            socket.send(bytes)
        }

        override fun onScreenChanged() {
            // write() runs on the main thread (the panel switches there before
            // feeding an emulator), so invalidating directly saves a message-loop
            // hop on every batch of output. While the window is hidden there is
            // no view to invalidate; the retained grid is updated anyway.
            view?.onContentChanged()
        }

        override fun onTitleChanged(title: String) = Unit

        override fun onClipboard(text: String) {
            val ctx = view?.context ?: return
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return
            cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
        }
    }

    /** A placeholder size until the view is measured and reports the real one. */
    val placeholder = TerminalEmulator(80, 24, output)

    fun emulatorFor(id: String): TerminalEmulator = emulators.getOrPut(id) {
        if (id.isBlank()) {
            placeholder
        } else {
            TerminalEmulator(80, 24, output).also { em ->
                // Reattaching to a session that is already running: put its
                // modes back before the engine's replay lands, so a full-screen
                // program keeps its alternate screen and a swipe stays a wheel.
                socket.rememberedModes(id)?.let(em::applyModes)
                grid?.let { (cols, rows) -> em.resize(cols, rows) }
            }
        }
    }

    /** A deliberately closed terminal starts clean on its next open. */
    fun reset() {
        emulators.clear()
        active = null
    }

    /**
     * Drains every tab's bytes into its grid. This belongs to the screen rather
     * than the visible window: while the user is in the chat the shells keep
     * printing, and their grids must keep up so returning shows the live screen
     * instead of replaying an unbounded backlog. Each tab has its own pump, so a
     * busy background tab cannot add latency to the one on screen.
     */
    suspend fun pump(sessions: List<String>) = coroutineScope {
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
                while (grid == null && waitedForSize < 1_000L) {
                    delay(16)
                    waitedForSize += 16
                }
                pumpTabOutput(output, emulatorFor(id))
            }
        }
    }
}

/**
 * A terminal on the machine the engine runs on, sliding in over the chat.
 *
 * The rendering and the emulation are the app's own — a native [TerminalView]
 * drawing a [TerminalEmulator] — so a finger is a mouse click, a key is a byte,
 * and nothing stands between the socket and the screen. Every tab owns its own
 * connection and emulator: switching tabs never redials, and every tab keeps
 * running while another is on screen.
 *
 * The shell is the engine's, on the engine's host — this is the phone looking at
 * that machine, not a shell on the phone.
 */
@Composable
fun TerminalWindow(
    socket: TerminalSocket,
    ui: TerminalUiState,
    onClose: () -> Unit,
) {
    val state by socket.state.collectAsStateWithLifecycle()
    val errorRes by socket.error.collectAsStateWithLifecycle()
    val detail by socket.detail.collectAsStateWithLifecycle()
    val activeSessionId by socket.activeSessionId.collectAsStateWithLifecycle()
    val sessionTitles by socket.sessionTitles.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var ready by remember { mutableStateOf<TermEvent.Ready?>(null) }
    var ended by remember { mutableStateOf<TermEvent?>(null) }

    // The view is created by Compose, the holder by the screen; each needs the
    // other, so the live view is also stored in the holder the emulators read.
    val terminalView = remember { mutableStateOf<TerminalView?>(null) }
    val ctrlOn = remember { mutableStateOf(false) }
    val altOn = remember { mutableStateOf(false) }
    val shiftOn = remember { mutableStateOf(false) }
    val atBottom = remember { mutableStateOf(true) }
    val selecting = remember { mutableStateOf(false) }
    val verticalDrag = remember { mutableStateOf(false) }

    // The drawer's drag is refused across the footer: a thumb hooking sideways
    // over the keys or the command field is reaching for those, not for the
    // menu. Over the grid the swipe is full width. It is measured from the
    // bottom of the window rather than as the footer's own height, because with
    // the keyboard up the field is lifted a whole IME inset off the bottom, and
    // only its top says where the refusal has to end.
    var footerBandPx by remember { mutableStateOf(0) }

    // TalkBack cannot see a custom-canvas terminal: the grid is drawn with no
    // nodes at all, so the whole screen was silent and a screen-reader user
    // could type into output they could not read. The visible rows are mirrored
    // into the view's content description while the window is open; the keys
    // stay the only controls, which is the same surface a sighted reader has.
    val spokenRows = remember { mutableStateOf("") }
    LaunchedEffect(ui.active) {
        while (true) {
            spokenRows.value = ui.active?.buffer?.spokenTail().orEmpty()
            delay(700)
        }
    }

    // One emulator per tab, not one for the window. A tab keeps receiving its
    // shell's bytes while another tab is on screen, so switching only swaps
    // which grid the view draws — no redial, no replay, no blank page.
    val emulators = ui.emulators
    val placeholder = ui.placeholder

    fun emulatorFor(id: String): TerminalEmulator = ui.emulatorFor(id)

    DisposableEffect(Unit) {
        // The foreground service keeps the process and every tab's socket alive
        // while the terminal has tabs. It is started here, on the first open,
        // and deliberately not stopped when the window is hidden: going back to
        // the chat must not hang up a live shell.
        TerminalLiveService.start(context)
        socket.open()
        onDispose {
            // The shell outlives this window; its modes must outlive it too.
            val id = socket.activeSessionId.value
            if (id.isNotBlank()) {
                emulators[id]?.let { socket.rememberModes(id, it.snapshotModes()) }
            }
            // The window is only being hidden (back to chat) or the whole
            // screen is going away; ChatScreen owns the sockets and closes them
            // on its own dispose, while Close ends the tabs explicitly
            // (closeWindow). Tearing them down here is what made returning to
            // the terminal redial and lose the screen.
            if (ui.view === terminalView.value) ui.view = null
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

    // Every open tab is drained by TerminalUiState.pump, owned by the screen so
    // a hidden terminal keeps its grids current. Switching to a tab therefore
    // shows the live screen at once instead of reattaching and replaying.

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
        ui.active = em
        terminalView.value?.setEmulator(em)
        terminalView.value?.scrollToBottom()
    }

    val currentOnClose by rememberUpdatedState(onClose)
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()

    // Closing the window closes the terminal: the shells are ended on the
    // engine and the saved tab list is cleared, so reopening the terminal
    // starts fresh instead of handing back the tabs that were closed.
    val closeWindow = {
        socket.endAllTabs()
        ui.reset()
        TerminalLiveService.stop(context)
        currentOnClose()
    }

    // Back hides the window instead of closing the terminal: the drawer closes
    // first, then the chat is shown with every tab still attached. The explicit
    // "Close terminal" action in the drawer is the only thing that ends shells.
    // Compose's BackHandler callbacks run newest-first, so this one is layered
    // above the chat screen's "leave terminal" handler; that one only fires if
    // this one is not composed.
    BackHandler {
        if (drawerState.isOpen || drawerState.targetValue != DrawerValue.Closed) {
            drawerScope.launch { drawerState.close() }
        } else {
            currentOnClose()
        }
    }

    val restart = {
        ended = null
        ready = null
        terminalView.value?.scrollToBottom()
        terminalView.value?.keepSoftKeyboardIfShown()
        socket.newSession()
    }
    val closeSession: (String) -> Unit = { id ->
        ended = null
        ready = null
        terminalView.value?.scrollToBottom()
        terminalView.value?.keepSoftKeyboardIfShown()
        // The grid belongs to a shell that is gone, so it goes with it.
        ui.emulators.remove(id)
        socket.closeSession(id)
    }
    val status = statusLine(state, errorRes, ready, ended) +
        (if (detail.isNotBlank() && state != TermState.OPEN) "  ·  $detail" else "")

    // The terminal itself is still one platform ViewGroup, but the chrome no
    // longer sits above it: the session controls live in a drawer so the shell
    // gets the whole page. A left-to-right swipe anywhere over the page opens
    // it; one begun on the keys or the command field does not.
    ModalNavigationDrawer(
        drawerState = drawerState,
        // The drawer's own drag is the one Material wrote — it follows the
        // finger and needs real travel to settle open. Once the terminal has
        // locked a gesture to the vertical axis the drawer steps aside
        // completely, since a sideways drift halfway through a scroll must not
        // become a menu swipe, and it does the same while text is selected.
        // Which of those drags it is allowed to see at all is decided by the
        // guard on the surface below: none begun on the footer.
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
                    title = sessionTitles[activeSessionId].orEmpty(),
                    onRestart = {
                        drawerScope.launch { drawerState.close() }
                        restart()
                    },
                    onCloseSession = { id ->
                        drawerScope.launch { drawerState.close() }
                        closeSession(id)
                    },
                    onClose = {
                        drawerScope.launch { drawerState.close() }
                        closeWindow()
                    },
                    onPicked = { drawerScope.launch { drawerState.close() } },
                )
            }
        },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Ink)
                // The guard moves nothing: it only refuses a sideways drag begun
                // on the footer, so the grid keeps its scroll, its selection and
                // its long press, a swipe from anywhere else on the page still
                // drives the drawer with the finger, and the keys and the command
                // field keep the thumb over them.
                .drawerFloorGuard(
                    drawerState,
                    bottomReservedPx = footerBandPx,
                    enabled = !selecting.value && !verticalDrag.value,
                ),
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    // The system bars set the floor and the ceiling. The nav bar
                    // keeps its strip or it covers the composer.
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .semantics { contentDescription = spokenRows.value },
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
                        setEmulator(ui.active ?: placeholder)
                        inputSink = { socket.send(it) }
                        onResize = { cols, rows ->
                            ui.grid = cols to rows
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
                            shiftOn.value = false
                        }
                        requestFocus()
                        terminalView.value = this
                        ui.view = this
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
                                                view.keepSoftKeyboardIfShown()
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
                                        shiftOn = shiftOn.value,
                                        onCtrl = {
                                            val v = !ctrlOn.value
                                            ctrlOn.value = v
                                            view.setLatchedMods(v, altOn.value, shiftOn.value)
                                            view.keepSoftKeyboardIfShown()
                                        },
                                        onAlt = {
                                            val v = !altOn.value
                                            altOn.value = v
                                            view.setLatchedMods(ctrlOn.value, v, shiftOn.value)
                                            view.keepSoftKeyboardIfShown()
                                        },
                                        onShift = {
                                            val v = !shiftOn.value
                                            shiftOn.value = v
                                            view.setLatchedMods(ctrlOn.value, altOn.value, v)
                                            view.keepSoftKeyboardIfShown()
                                        },
                                        onShiftSpent = {
                                            // The backtab went out; the latch has no key left.
                                            shiftOn.value = false
                                            view.setLatchedMods(ctrlOn.value, altOn.value, false)
                                        },
                                        onKey = { bytes ->
                                            socket.send(bytes)
                                            // Tapping a key must not take the keyboard with it.
                                            view.keepSoftKeyboardIfShown()
                                        },
                                    )
                                    TerminalComposer(
                                        socket = socket,
                                        onSend = {
                                            if (ctrlOn.value || altOn.value || shiftOn.value) {
                                                ctrlOn.value = false
                                                altOn.value = false
                                                shiftOn.value = false
                                                view.setLatchedMods(false, false, false)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    // Every re-layout of the floor is reported upward, so the
                    // band ends at the keys and the field rather than running
                    // down through them: the composer grows with a multi-line
                    // command and the whole footer rides up when the keyboard
                    // arrives, and neither is a reach for the menu.
                    footer.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                        val inWindow = IntArray(2)
                        v.getLocationInWindow(inWindow)
                        footerBandPx = (v.rootView.height - inWindow[1]).coerceAtLeast(0)
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
            // Parsing stays on the main thread — the emulator hands each change
            // straight to the view — but a large `cat` no longer blocks a whole
            // frame: the batch is fed in slices and the thread yields between
            // them, so frames land while a build log scrolls.
            withContext(Dispatchers.Main) {
                var at = 0
                while (at < batch.size) {
                    val end = minOf(at + WRITE_CHUNK, batch.size)
                    emulator.write(batch.copyOfRange(at, end))
                    at = end
                    if (at < batch.size) yield()
                }
            }
        }
    } finally {
        pump.cancel()
    }
}

/**
 * The drawer's one screen: which shells there are and how to get another, then
 * why the link looks the way it does, then the way out.
 *
 * The session list is the first thing below the title because it is what the
 * thumb comes for, and a tab is closed by holding it rather than by a button of
 * its own: the row already names the shell, so a second control that means
 * "close the one you were just looking at" only asks to be mis-tapped.
 */
@Composable
private fun TerminalSidebar(
    socket: TerminalSocket,
    status: String,
    state: TermState,
    title: String,
    onRestart: () -> Unit,
    onCloseSession: (String) -> Unit,
    onClose: () -> Unit,
    onPicked: () -> Unit,
) {
    // The tab a hold picked out, kept with the words that name it so the
    // confirmation can say which shell is about to stop.
    var pendingClose by remember { mutableStateOf<Pair<String, String>?>(null) }

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

        // What the running program calls itself (OSC 0/2). tmux answers the
        // program itself and keeps the title, so it lives here beside the shell
        // rather than on a bar of its own over the grid.
        if (title.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Surface(
                color = InkRaised,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, InkLine),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        stringResource(R.string.terminal_window),
                        style = MaterialTheme.typography.labelSmall,
                        color = InkDim,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
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
            TerminalSessionBar(
                socket = socket,
                onPicked = onPicked,
                onLongPressed = { id, label -> pendingClose = id to label },
            )

            Spacer(Modifier.height(24.dp))
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

    val held = pendingClose
    if (held != null) {
        AlertDialog(
            onDismissRequest = { pendingClose = null },
            containerColor = InkRaised,
            titleContentColor = InkText,
            textContentColor = InkDim,
            title = { Text(stringResource(R.string.terminal_close_session_title)) },
            text = {
                Column {
                    Text(held.second, style = MaterialTheme.typography.bodyMedium, color = InkText)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.terminal_close_session_body))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingClose = null
                        onCloseSession(held.first)
                    },
                ) {
                    Text(stringResource(R.string.terminal_close_session), color = Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingClose = null }) {
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
            tint = InkDim,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = InkText,
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
 * Shift latches the same way, and its one key is Tab: Shift then Tab sends
 * the backtab (ESC[Z) that reverses an edit's completion or a TUI's strip.
 * Every arrow is holdable: keep a thumb on it and it repeats.
 */
@Composable
private fun TerminalKeys(
    ctrlOn: Boolean,
    altOn: Boolean,
    shiftOn: Boolean,
    onCtrl: () -> Unit,
    onAlt: () -> Unit,
    onShift: () -> Unit,
    onShiftSpent: () -> Unit,
    onKey: (ByteArray) -> Unit,
) {
    // Exactly two rows, seven keys each. The navigation cluster owns the
    // middle: page-up, up and page-down sit above left, down and right, so the
    // up arrow stays directly above the down arrow and the thumb finds all six
    // without reaching for an edge. The modifiers hold the left of the lower
    // row in the order Shift, Ctrl, Alt. The two job keys share the right edge:
    // the whole-line erase (Ctrl-U then Ctrl-K, so it works from the middle
    // too) sits above the one that replaces the running shell with a fresh
    // login shell in place — exec $SHELL — which is how a wedged or
    // half-configured session gets a clean one without a new tab. Every arrow
    // and page key holds and repeats: nobody walks a file one tap at a time.
    val tab = if (shiftOn) "\u001b[Z".toByteArray() else byteArrayOf(9)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    KeyChip("Esc", wide = true, onClick = { onKey(byteArrayOf(27)) })
                }
                Box(Modifier.weight(1f)) {
                    KeyChip("Tab", wide = true, onClick = {
                        onKey(tab)
                        // Only Tab answers to Shift: once the backtab has gone
                        // out, the latch has nothing left to colour.
                        if (shiftOn) onShiftSpent()
                    })
                }
                Box(Modifier.weight(1f)) {
                    KeyChip("CMD", wide = true, onClick = { onKey(byteArrayOf(16)) })
                }
                Box(Modifier.weight(1f)) {
                    HoldableKeyChip("PgUp", onClick = { onKey("\u001b[5~".toByteArray()) })
                }
                Box(Modifier.weight(1f)) {
                    HoldableKeyChip("\u2191", onClick = { onKey("\u001b[A".toByteArray()) })
                }
                Box(Modifier.weight(1f)) {
                    HoldableKeyChip("PgDn", onClick = { onKey("\u001b[6~".toByteArray()) })
                }
                Box(Modifier.weight(1f)) {
                    IconKey(
                        icon = Icons.Default.ClearAll,
                        contentDescription = "Delete line",
                        onClick = { onKey(byteArrayOf(21, 11)) },
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    KeyChip("Shift", active = shiftOn, wide = true, onClick = onShift)
                }
                Box(Modifier.weight(1f)) {
                    KeyChip("Ctrl", active = ctrlOn, wide = true, onClick = onCtrl)
                }
                Box(Modifier.weight(1f)) {
                    KeyChip("Alt", active = altOn, wide = true, onClick = onAlt)
                }
                Box(Modifier.weight(1f)) {
                    HoldableKeyChip("\u2190", onClick = { onKey("\u001b[D".toByteArray()) })
                }
                Box(Modifier.weight(1f)) {
                    HoldableKeyChip("\u2193", onClick = { onKey("\u001b[B".toByteArray()) })
                }
                Box(Modifier.weight(1f)) {
                    HoldableKeyChip("\u2192", onClick = { onKey("\u001b[C".toByteArray()) })
                }
                Box(Modifier.weight(1f)) {
                    IconKey(
                        icon = Icons.Default.Refresh,
                        contentDescription = "New login shell",
                        onClick = { onKey("exec \$SHELL\r".toByteArray()) },
                    )
                }
            }
        }
    }
}

/**
 * An icon-only key, drawn to the same size as a [KeyChip] so the bar stays
 * even: the fresh-shell key and the whole-line erase are symbols rather than
 * words, and a word would not fit beside the arrows.
 */
@Composable
private fun IconKey(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
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
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = contentDescription,
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
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
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
 * An arrow: a tap sends it once, and a held thumb repeats it until the finger
 * lifts — nobody walks through a long file one tap at a time. The events wrap
 * the repeat, so a lifted finger (or a canceled gesture, a drag away) ends it
 * between windows without a last stray key; jitter only fills a window sooner.
 * It is drawn to the same size as a [KeyChip] so both rows stay even.
 */
@Composable
private fun HoldableKeyChip(label: String, onClick: () -> Unit) {
    val currentClick by rememberUpdatedState(onClick)
    Surface(
        color = InkLine,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            // A raw pointerInput has no node to focus and no click action, so
            // TalkBack, switch access and a keyboard could not press these
            // arrows at all. Touch keeps the hold-to-repeat gesture; this is the
            // same key for every input that cannot hold.
            .semantics {
                role = Role.Button
                contentDescription = label
                semanticsOnClick(label = label) {
                    currentClick()
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    currentClick()
                    // The first grace is what tells a tap from a hold: after
                    // it the chip fires once per repeat window, and the
                    // windows are wall-clock marks rather than silences, so
                    // pointer noise (drift, a start of a drag) cannot starve
                    // the repeat out. A lift is the only way to end it.
                    var nextMark = System.currentTimeMillis() + KEY_REPEAT_FIRST_MS
                    while (true) {
                        val wait = (nextMark - System.currentTimeMillis()).coerceAtLeast(0L)
                        val event = withTimeoutOrNull(wait) { awaitPointerEvent() }
                        if (event == null) {
                            currentClick()
                            nextMark = System.currentTimeMillis() + KEY_REPEAT_MS
                            continue
                        }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = InkText,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * A line the phone keyboard can type into. Enter is the ordinary return key, not
 * an IME "send" key: at the end of the text it hands the line to the shell, and
 * anywhere else it is a line break, so a multi-line command can be written first.
 */
@Composable
private fun TerminalComposer(socket: TerminalSocket, onSend: () -> Unit = {}) {
    var field by remember { mutableStateOf(TextFieldValue("")) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = field,
            onValueChange = { next ->
                // The keyboard reports the text only after the key is already
                // in it, so the caret that pressed Enter is read from the old
                // value. At the end of the text Enter runs the command; in the
                // middle it is a line break, letting a loop or here-doc be
                // built over several lines before it goes to the shell.
                val old = field
                val enterAtEnd = old.selection.collapsed &&
                    old.selection.end == old.text.length &&
                    next.text.length == old.text.length + 1 &&
                    next.text.startsWith(old.text) &&
                    next.text.endsWith('\n')
                if (enterAtEnd) {
                    // CR, not LF: this is the same byte the terminal's Enter
                    // sends, so an empty line submits exactly like a real one.
                    socket.send((old.text.replace('\n', '\r') + "\r").toByteArray())
                    onSend()
                    field = TextFieldValue("")
                } else {
                    field = next
                }
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
private fun TerminalSessionBar(
    socket: TerminalSocket,
    onPicked: () -> Unit,
    onLongPressed: (id: String, label: String) -> Unit,
) {
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
            // quick tapping; the pid and the server's own name for the shell
            // identify the process behind it.
            val pid = pids[id]
            val pidText = if (pid != null && pid > 0) {
                stringResource(R.string.terminal_session_pid, pid)
            } else {
                stringResource(R.string.terminal_session_starting)
            }
            val subtitle = "$pidText  ·  ${shellHandle(id)}"
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
                    .combinedClickable(
                        onClick = {
                            socket.switchSession(id)
                            onPicked()
                        },
                        onLongClick = { onLongPressed(id, "Shell ${i + 1}  ·  $subtitle") },
                    ),
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
                            subtitle,
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
        Text(
            stringResource(R.string.terminal_sessions_hold),
            style = MaterialTheme.typography.labelSmall,
            color = InkDim,
        )
    }
}

/**
 * What the shell server calls this tab. tmux spent a row of the grid on a
 * status bar carrying exactly this name, so the drawer names each shell the way
 * `tmux ls` does instead of leaving that row to the bar.
 */
internal fun shellHandle(id: String, maxLength: Int = 16): String {
    val name = buildString(id.length + 4) {
        append("aix-")
        for (c in id) {
            append(if (c.isLetterOrDigit() && c.code < 128 || c == '-' || c == '_') c else '_')
        }
    }
    return if (name.length <= maxLength) name else name.take(maxLength - 1) + "…"
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
