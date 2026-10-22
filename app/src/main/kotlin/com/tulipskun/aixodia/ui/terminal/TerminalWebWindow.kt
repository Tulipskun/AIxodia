package com.tulipskun.aixodia.ui.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.tulipskun.aixodia.SettingsStore
import com.tulipskun.aixodia.data.remote.HistoryApi
import com.tulipskun.aixodia.term.TerminalLiveService
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private val Ink = Color(0xFF0B0F14)
private val InkLine = Color(0xFF1A222C)
private val InkText = Color(0xFFD7E0EA)
private val InkDim = Color(0xFF8CA0B3)

private sealed interface WebEvent {
    data object Open : WebEvent
    data class Ready(val shell: String, val cwd: String, val id: String, val cols: Int, val rows: Int, val pid: Int) : WebEvent
    data object Disconnected : WebEvent
    data class Ended(val kind: String, val detail: String) : WebEvent
}

private class TerminalBridge(private val events: Channel<WebEvent>) {
    @JavascriptInterface
    fun onOpen() {
        events.trySend(WebEvent.Open)
    }

    @JavascriptInterface
    fun onReady(shell: String, cwd: String, id: String, cols: Int, rows: Int, pid: Int) {
        events.trySend(WebEvent.Ready(shell, cwd, id, cols, rows, pid))
    }

    @JavascriptInterface
    fun onDisconnected() {
        events.trySend(WebEvent.Disconnected)
    }

    @JavascriptInterface
    fun onEnded(kind: String, detail: String) {
        events.trySend(WebEvent.Ended(kind, detail))
    }
}

/**
 * A terminal rendered by xterm.js inside a WebView. It owns a dedicated
 * WebSocket to `/terminal`, authenticated by a single-use ticket, so the
 * long-lived API token never appears in a URL. The shell itself lives in tmux
 * on the engine; closing this window (or the app) does not end it.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TerminalWebWindow(
    settings: SettingsStore,
    history: HistoryApi,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val events = remember { Channel<WebEvent>(Channel.UNLIMITED) }
    val bridge = remember { TerminalBridge(events) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    val ids = remember { mutableStateListOf<String>() }
    var active by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Connecting…") }
    var pageReady by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    var terminalSize by remember { mutableStateOf(80 to 24) }

    fun newID(): String = "t-" + UUID.randomUUID().toString().replace("-", "").take(12)

    fun saveIDs() {
        scope.launch { settings.saveTerminalSessionIds(ids.toList()) }
    }

    fun chooseNext(remove: String?) {
        if (remove != null) ids.remove(remove)
        if (ids.isEmpty()) ids.add(newID())
        active = ids.lastOrNull() ?: newID()
        ended = false
        attempt = 0
        saveIDs()
    }

    fun closeTab(id: String) {
        val wasActive = id == active
        if (wasActive) {
            webViewRef.value?.evaluateJavascript("closeTerminal()", null)
        }
        scope.launch {
            runCatching { history.closeTerminal(id) }
            runCatching { settings.removeTerminalSessionId(id) }
            chooseNext(id)
        }
    }

    LaunchedEffect(Unit) {
        val saved = runCatching { settings.currentTerminalSessionIds() }.getOrDefault(emptyList())
        val remote = runCatching { history.terminals() }
            .getOrDefault(emptyList())
            .mapNotNull { it.optString("id").takeIf { id -> id.isNotBlank() } }
        val merged = (saved + remote).distinct()
        ids.clear()
        if (merged.isEmpty()) ids.add(newID()) else ids.addAll(merged)
        active = saved.lastOrNull()?.takeIf { it in ids } ?: ids.first()
        saveIDs()
    }

    DisposableEffect(Unit) {
        TerminalLiveService.start(context)
        onDispose {
            TerminalLiveService.stop(context)
            webViewRef.value?.apply {
                loadUrl("about:blank")
                destroy()
            }
            webViewRef.value = null
            scope.launch { runCatching { settings.saveTerminalSessionIds(ids.toList()) } }
        }
    }

    LaunchedEffect(active, pageReady, attempt) {
        if (!pageReady || active.isBlank() || ended) return@LaunchedEffect
        if (attempt > 0) delay(1200L)
        val id = active
        status = if (attempt == 0) "Connecting…" else "Reconnecting…"
        val ticket = runCatching { history.terminalTicket() }.getOrNull()
        if (ticket.isNullOrBlank()) {
            status = "Engine unreachable — retrying"
            delay(2500L)
            if (active == id && !ended) attempt++
            return@LaunchedEffect
        }
        val cfg = runCatching { settings.current() }.getOrNull()
        val base = cfg?.daemonUrl?.trim()?.trimEnd('/').orEmpty()
        if (base.isBlank()) {
            status = "No daemon address"
            return@LaunchedEffect
        }
        val wsBase = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> return@LaunchedEffect
        }
        val view = webViewRef.value ?: return@LaunchedEffect
        if (view.width > 0) {
            // The WebView measures in pixels, xterm sizes itself in CSS pixels,
            // and one of those is one dp on this side. Dividing raw pixels
            // asked the shell for a 135-column window on a 411dp screen, which
            // then had to be corrected on the first frame.
            val density = context.resources.displayMetrics.density
            terminalSize = (view.width / density / 8f).toInt().coerceAtLeast(20) to
                (view.height / density / 18f).toInt().coerceAtLeast(5)
        }
        val (cols, rows) = terminalSize
        val url = buildString {
            append(wsBase)
            append("/terminal?ticket=")
            append(URLEncoder.encode(ticket, "UTF-8"))
            append("&id=")
            append(URLEncoder.encode(id, "UTF-8"))
            append("&cols=").append(cols)
            append("&rows=").append(rows)
        }
        val quoted = JSONObject.quote(url)
        view.post {
            view.evaluateJavascript("startTerminal($quoted,$cols,$rows)", null)
        }
    }

    LaunchedEffect(Unit) {
        for (event in events) {
            when (event) {
                is WebEvent.Open -> {
                    ended = false
                    status = "Connected"
                }
                is WebEvent.Ready -> {
                    ended = false
                    status = if (event.cwd.isBlank()) event.shell else "${event.shell}  ·  ${event.cwd}"
                    if (event.id.isNotBlank() && event.id != active) {
                        val at = ids.indexOf(active)
                        if (at >= 0) ids[at] = event.id else ids.add(event.id)
                        active = event.id
                        saveIDs()
                    } else if (event.id.isNotBlank()) {
                        scope.launch { settings.saveTerminalSessionId(event.id) }
                    }
                }
                is WebEvent.Disconnected -> {
                    if (!ended) {
                        status = "Disconnected — reconnecting…"
                        attempt++
                    }
                }
                is WebEvent.Ended -> {
                    ended = true
                    status = if (event.kind == "exit") "Session ended (${event.detail})" else event.detail
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Ink).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            Modifier.fillMaxWidth().background(InkLine).padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (id in ids) {
                    val selected = id == active
                    Row(
                        Modifier
                            .clickable {
                                if (id != active) {
                                    ended = false
                                    attempt = 0
                                    active = id
                                    status = "Connecting…"
                                }
                            }
                            .background(if (selected) Ink else Color.Transparent)
                            .padding(start = 8.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            id.takeLast(8),
                            color = if (selected) InkText else InkDim,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        IconButton(
                            onClick = { closeTab(id) },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close terminal", tint = InkDim)
                        }
                    }
                }
            }
            IconButton(
                onClick = {
                    val id = newID()
                    ids.add(id)
                    ended = false
                    attempt = 0
                    active = id
                    saveIDs()
                },
            ) {
                Icon(Icons.Default.Add, contentDescription = "New terminal", tint = InkText)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Back to chat", tint = InkText)
            }
        }
        Text(
            status,
            color = InkDim,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp),
        )
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                // The page fits the terminal to its box. A layout change here
                // (rotation, keyboard, unfolded screen) is a new box even when
                // the page's own resize event never fires.
                .onSizeChanged { webViewRef.value?.evaluateJavascript("fitAndResize()", null) },
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(AndroidColor.parseColor("#0B0F14"))
                    this.settings.javaScriptEnabled = true
                    this.settings.domStorageEnabled = true
                    this.settings.allowFileAccess = true
                    this.settings.allowFileAccessFromFileURLs = false
                    this.settings.allowContentAccess = false
                    this.settings.mediaPlaybackRequiresUserGesture = false
                    // Honour width=device-width so one CSS pixel is one dp and
                    // the fit addon measures the window the user can see.
                    this.settings.useWideViewPort = true
                    this.settings.loadWithOverviewMode = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            pageReady = true
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                        ): Boolean = request?.url?.scheme?.let { it != "file" && it != "about" } ?: false
                    }
                    addJavascriptInterface(bridge, "AndroidTerminal")
                    webViewRef.value = this
                    loadUrl("file:///android_asset/terminal/index.html")
                }
            },
            update = { view ->
                webViewRef.value = view
            },
        )
    }
}
