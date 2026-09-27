package com.tulipskun.aixodia.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.squareup.moshi.Moshi
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

@JsonClass(generateAdapter = true)
data class ScreenRequest(val session_id: String = "", val goal: String = "")

@JsonClass(generateAdapter = true)
data class ScreenResponse(
    val ok: Boolean,
    val action: String = "",
    val detail: String = "",
    val error: String = "",
)

private data class Candidate(
    val label: String,
    val node: AccessibilityNodeInfo? = null,
    val kind: String = "tap",
)

class ScreenAutomationService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private lateinit var engine: LocalJevEngine
    private val moshi = Moshi.Builder().build()
    private val requestAdapter = moshi.adapter(ScreenRequest::class.java)
    private val responseAdapter = moshi.adapter(ScreenResponse::class.java)

    override fun onServiceConnected() {
        super.onServiceConnected()
        engine = LocalJevEngine(this)
        scope.launch { engine.prepare() }
        startBridge()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun startBridge() {
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                server = ServerSocket(18790, 8, java.net.InetAddress.getByName("127.0.0.1"))
                while (running.get()) {
                    val socket = server?.accept() ?: break
                    scope.launch(Dispatchers.IO) { handle(socket) }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use {
            try {
                val input = it.getInputStream().bufferedReader()
                val first = input.readLine() ?: return
                if (!first.startsWith("POST /screen/control")) {
                    write(it, 404, ScreenResponse(false, error = "not found"))
                    return
                }
                var length = 0
                while (true) {
                    val line = input.readLine() ?: return
                    if (line.isEmpty()) break
                    if (line.lowercase().startsWith("content-length:")) {
                        length = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }
                val body = CharArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n <= 0) break
                    read += n
                }
                val req = requestAdapter.fromJson(String(body, 0, read)) ?: ScreenRequest()
                val result = runBlocking { control(req.goal) }
                write(it, 200, result)
            } catch (e: Exception) {
                write(it, 500, ScreenResponse(false, error = e.message ?: "screen bridge error"))
            }
        }
    }

    private fun write(socket: Socket, code: Int, response: ScreenResponse) {
        val body = responseAdapter.toJson(response)
        val out = socket.getOutputStream().bufferedWriter()
        out.write("HTTP/1.1 " + code + " " + if (code == 200) "OK" else "ERROR" + "\r\n")
        out.write("Content-Type: application/json\r\n")
        out.write("Content-Length: " + body.toByteArray().size + "\r\n")
        out.write("Connection: close\r\n\r\n")
        out.write(body)
        out.flush()
    }

    private suspend fun control(goal: String): ScreenResponse {
        if (goal.isBlank()) return ScreenResponse(false, error = "goal is empty")
        val root = rootInActiveWindow ?: return ScreenResponse(false, error = "no active window")
        val candidates = mutableListOf<Candidate>()
        collect(root, candidates)
        candidates.add(Candidate("scroll down", kind = "scroll_down"))
        candidates.add(Candidate("scroll up", kind = "scroll_up"))
        candidates.add(Candidate("go back", kind = "back"))
        candidates.add(Candidate("go home", kind = "home"))
        val limited = candidates.take(16)
        if (limited.isEmpty()) return ScreenResponse(false, error = "no screen actions available")
        val labels = limited.mapIndexed { i, c -> "$i: " + c.label }
        val decision = engine.choose(
            "Goal: " + goal + "\nCurrent Android accessibility UI:\n" + labels.joinToString("\n"),
            labels,
        ).getOrElse { return ScreenResponse(false, error = it.message ?: "JEV not ready") }
        val index = decision.first.coerceIn(0, limited.lastIndex)
        val c = limited[index]
        val ok = when (c.kind) {
            "tap" -> click(c.node)
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "scroll_down" -> swipe(true)
            "scroll_up" -> swipe(false)
            else -> false
        }
        return if (ok) {
            ScreenResponse(true, c.kind, c.label + " (confidence " + decision.second + ")")
        } else {
            ScreenResponse(false, c.kind, error = "action failed: " + c.label)
        }
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<Candidate>) {
        if (out.size >= 12) return
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val label = when {
            text.isNotEmpty() -> text
            desc.isNotEmpty() -> desc
            node.isEditable -> "editable field"
            else -> ""
        }
        if (label.isNotEmpty() && (node.isClickable || node.isFocusable || node.isEditable)) {
            out += Candidate(label.take(80), node)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collect(it, out) }
        }
    }

    private fun click(node: AccessibilityNodeInfo?): Boolean {
        var n = node
        while (n != null) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
        }
        return false
    }

    private fun swipe(down: Boolean): Boolean {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val startY = if (down) dm.heightPixels * .30f else dm.heightPixels * .70f
        val endY = if (down) dm.heightPixels * .70f else dm.heightPixels * .30f
        val path = Path().apply { moveTo(x, startY); lineTo(x, endY) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 450)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    override fun onDestroy() {
        running.set(false)
        server?.close()
        if (::engine.isInitialized) engine.close()
        super.onDestroy()
    }
}
