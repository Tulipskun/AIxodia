package com.tulipskun.aixodia.ui.terminal

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.ime
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
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tulipskun.aixodia.R
import com.tulipskun.aixodia.data.remote.TermEvent
import com.tulipskun.aixodia.data.remote.TermState
import com.tulipskun.aixodia.data.remote.TerminalSocket
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
 * The window owns the connection: it dials when it appears and hangs up when it
 * goes away, so a shell never outlives the screen that shows it. The shell is
 * the engine's, on the engine's host — this is the phone looking at that
 * machine, not a shell on the phone.
 */
@Composable
fun TerminalWindow(socket: TerminalSocket, onClose: () -> Unit) {
    val state by socket.state.collectAsStateWithLifecycle()
    val errorRes by socket.error.collectAsStateWithLifecycle()
    val detail by socket.detail.collectAsStateWithLifecycle()
    val activeSessionId by socket.activeSessionId.collectAsStateWithLifecycle()
    val sessions by socket.sessions.collectAsStateWithLifecycle()

    var ready by remember { mutableStateOf<TermEvent.Ready?>(null) }
    var ended by remember { mutableStateOf<TermEvent?>(null) }
    val page = remember { mutableStateOf<WebView?>(null) }
    val pageReady = remember { mutableStateOf(false) }

    // Both of these are owned by the page and reported back across the bridge:
    // the latching Ctrl/Alt modifiers, and whether the view is at the live edge.
    // Keeping them here lets the key row and the jump button act on the page.
    val ctrlOn = remember { mutableStateOf(false) }
    val altOn = remember { mutableStateOf(false) }
    val atBottom = remember { mutableStateOf(true) }

    // Switching sessions must not leave the previous shell's bytes on screen:
    // reset xterm so the new session (and its replayed ring buffer) renders
    // cleanly instead of being appended to stale output.
    val lastSession = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(activeSessionId) {
        if (lastSession.value != null && activeSessionId != lastSession.value && page.value != null) {
            page.value?.evaluateJavascript("aixodiaReset()", null)
            ended = null
            ready = null
        }
        if (activeSessionId.isNotBlank()) lastSession.value = activeSessionId
    }
    var leaving by remember { mutableStateOf(false) }
    var appeared by remember { mutableStateOf(false) }

    // Open on the way in, hang up on the way out: closing the panel ends the
    // shell, which is the only way to be sure nothing is left running.
    DisposableEffect(Unit) {
        socket.open()
        onDispose {
            // A WebView has to leave the hierarchy before it is destroyed, and
            // Compose removes it on its own schedule — so it is detached here
            // rather than leaked behind the panel.
            page.value?.let {
                (it.parent as? ViewGroup)?.removeView(it)
                it.removeJavascriptInterface("aixodia")
                it.destroy()
            }
            page.value = null
            socket.close()
        }
    }

    LaunchedEffect(Unit) { appeared = true }
    // The socket reconnects on its own when the link drops or the daemon moves,
    // including while the app is in the background, so there is nothing to poke
    // when it comes back. Keeping a reconnect here would only restart a shell
    // that finished on purpose.
    LaunchedEffect(leaving) {
        if (leaving) {
            delay(240)
            onClose()
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

    // Terminal output is raw bytes, so it crosses the bridge as base64 and is
    // written as bytes. A shell answers one keystroke in several small writes —
    // the echoed byte, then the prompt's redraw — and one evaluateJavascript per
    // write is what makes typing stutter, because every one of them lands on the
    // main thread. Bytes are gathered for a few milliseconds and handed over as
    // one write instead, and the base64 happens off the main thread so only the
    // bridge call itself is there. Nothing is consumed until the page can draw,
    // which keeps a prompt that answers in the first few milliseconds from being
    // dropped on the floor.
    LaunchedEffect(pageReady.value) {
        if (!pageReady.value) return@LaunchedEffect
        val pending = ByteArrayOutputStream(8 * 1024)
        val lock = Any()
        // Conflated: a burst only needs to wake the writer once, and the writer
        // drains everything that arrived while it was busy.
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
                // Let a burst settle into a single write. A lone keystroke echo
                // is alone on the wire, so this is the worst case it adds.
                delay(4)
                val batch = synchronized(lock) {
                    val b = pending.toByteArray()
                    pending.reset()
                    b
                }
                if (batch.isEmpty()) continue
                val b64 = withContext(Dispatchers.Default) {
                    Base64.encodeToString(batch, Base64.NO_WRAP)
                }
                page.value?.evaluateJavascript("aixodiaWrite('$b64')", null)
            }
        } finally {
            pump.cancel()
        }
    }

    // While the soft keyboard is up the title and the session tabs step aside:
    // they are the least useful chrome exactly when the screen is at its
    // scarcest, and the terminal — the thing being typed into — gets the room
    // instead. A full-screen program like opencode then fills what is left.
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    AnimatedVisibility(
        visible = appeared && !leaving,
        enter = slideInHorizontally(animationSpec = tween(220), initialOffsetX = { it }),
        exit = slideOutHorizontally(animationSpec = tween(240), targetOffsetX = { it }),
    ) {
        Surface(color = Ink, modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                if (!imeVisible) {
                    TerminalHeader(
                        status = statusLine(state, errorRes, ready, ended) +
                            (if (detail.isNotBlank()) "  ·  $detail" else ""),
                        canRestart = state == TermState.CLOSED || state == TermState.ENDED,
                        onRestart = {
                            ended = null
                            ready = null
                            page.value?.evaluateJavascript("aixodiaReset()", null)
                            page.value?.requestFocus()
                            socket.newSession()
                        },
                        onCloseSession = {
                            ended = null
                            ready = null
                            page.value?.evaluateJavascript("aixodiaReset()", null)
                            page.value?.requestFocus()
                            socket.closeSession()
                        },
                        onClose = { leaving = true },
                    )
                    TerminalSessionBar(socket = socket)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    TerminalPage(
                        page = page,
                        pageReady = pageReady,
                        socket = socket,
                        ctrlOn = ctrlOn,
                        altOn = altOn,
                        atBottom = atBottom,
                    )
                    // Only while there is history above: a terminal sitting at
                    // the prompt needs no button, and one over the output would
                    // cover the text being read.
                    if (!atBottom.value) {
                        Surface(
                            color = Color(0xFF1A222C),
                            shape = RoundedCornerShape(50),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 12.dp, bottom = 12.dp)
                                .clickable {
                                    page.value?.evaluateJavascript("aixodiaScrollBottom()", null)
                                    atBottom.value = true
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
                TerminalKeys(
                    ctrlOn = ctrlOn.value,
                    altOn = altOn.value,
                    onCtrl = {
                        val v = !ctrlOn.value
                        ctrlOn.value = v
                        page.value?.evaluateJavascript("aixodiaSetMods($v, ${altOn.value})", null)
                    },
                    onAlt = {
                        val v = !altOn.value
                        altOn.value = v
                        page.value?.evaluateJavascript("aixodiaSetMods(${ctrlOn.value}, $v)", null)
                    },
                    onKey = { bytes ->
                        socket.send(bytes)
                        // Tapping a key must not take the keyboard with it: the
                        // focus goes straight back to the terminal.
                        page.value?.requestFocus()
                        page.value?.evaluateJavascript("aixodiaFocus()", null)
                    },
                )
                TerminalComposer(
                    socket = socket,
                    // The composer sends a finished line directly, bypassing the
                    // page's input path, so a latch left over from the keyboard
                    // would stay lit for no reason.
                    onSend = {
                        if (ctrlOn.value || altOn.value) {
                            ctrlOn.value = false
                            altOn.value = false
                            page.value?.evaluateJavascript("aixodiaSetMods(false, false)", null)
                        }
                    },
                )
            }
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
 * Ctrl and Alt latch for one keystroke: tap Ctrl, then type C, and the page
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
 * an IME "send" key: the newline that ends a line belongs to the shell, so the
 * field never keeps one and pressing return runs what was typed, the way it does
 * in any terminal.
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
                // and keep only what is still being typed.
                var rest = value
                var nl = rest.indexOf('\n')
                while (nl >= 0) {
                    val line = rest.substring(0, nl)
                    if (line.isNotEmpty()) {
                        socket.send((line + "\n").toByteArray())
                        onSend()
                    }
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
    val active by socket.activeSessionId.collectAsStateWithLifecycle()
    FlowRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        sessions.forEachIndexed { i, id ->
            val label = "Shell ${i + 1}"
            val selected = id == active || (active.isBlank() && i == sessions.lastIndex)
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

/**
 * The xterm.js page. It is a local asset — nothing is loaded from the network —
 * and the bridge is the only way in or out: base64 keystrokes out, base64 output
 * in, and a size report whenever the window changes.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TerminalPage(
    page: MutableState<WebView?>,
    pageReady: MutableState<Boolean>,
    socket: TerminalSocket,
    ctrlOn: MutableState<Boolean>,
    altOn: MutableState<Boolean>,
    atBottom: MutableState<Boolean>,
) {
    val main = remember { Handler(Looper.getMainLooper()) }
    val bridge = remember {
        object {
            @JavascriptInterface
            fun send(b64: String) {
                // Called on the WebView's bridge thread; send() is thread-safe
                // and must not wait for the UI.
                runCatching { Base64.decode(b64, Base64.NO_WRAP) }
                    .onSuccess { socket.send(it) }
            }

            @JavascriptInterface
            fun resize(cols: Int, rows: Int) {
                socket.resize(cols, rows)
            }

            // The page reports these back after it has acted on them, so the
            // key row and the jump button always mirror the page's own state.
            @JavascriptInterface
            fun mods(ctrl: Boolean, alt: Boolean) {
                main.post {
                    ctrlOn.value = ctrl
                    altOn.value = alt
                }
            }

            @JavascriptInterface
            fun scrollState(atBottomNow: Boolean) {
                main.post { atBottom.value = atBottomNow }
            }

            @JavascriptInterface
            fun ready() {
                main.post {
                    pageReady.value = true
                    page.value?.requestFocus()
                    page.value?.evaluateJavascript("aixodiaFocus()", null)
                }
            }
        }
    }
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(0xFF0B0F14.toInt())
                // Tap the screen to type: the WebView has to be able to take
                // focus, or the soft keyboard has nothing to appear for.
                isFocusable = true
                isFocusableInTouchMode = true
                // The page itself never scrolls (the document is exactly one
                // screen tall; the scrollback scrolls inside xterm), so the
                // WebView gets no scroll bar and no over-scroll glow.
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                // The page is a local asset and has no reason to navigate; this
                // also keeps a stray link from turning the terminal into a browser.
                webViewClient = WebViewClient()
                addJavascriptInterface(bridge, "aixodia")
                loadUrl("file:///android_asset/terminal/index.html")
                page.value = this
            }
        },
        update = { },
        modifier = Modifier.fillMaxSize(),
    )
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
    state == TermState.OPEN -> listOfNotNull(ready?.shell, ready?.cwd)
        .filter { it.isNotBlank() }
        .joinToString("  ")
    state == TermState.CONNECTING -> stringResource(R.string.terminal_connecting)
    errorRes != 0 -> stringResource(errorRes)
    state == TermState.ENDED -> stringResource(R.string.terminal_closed)
    else -> ""
}
