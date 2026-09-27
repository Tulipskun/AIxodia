package com.tulipskun.aixodia.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

@JsonClass(generateAdapter = true)
data class ScreenRequest(
    val action: String = "",
    val target_id: String = "",
    val x: Float = -1f,
    val y: Float = -1f,
    val x2: Float = -1f,
    val y2: Float = -1f,
    val duration_ms: Long = 450,
    val text: String = "",
)

@JsonClass(generateAdapter = true)
data class ScreenElement(
    val id: String,
    val label: String = "",
    val role: String = "view",
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 0f,
    val height: Float = 0f,
)

@JsonClass(generateAdapter = true)
data class ScreenObservation(
    val ok: Boolean,
    val width: Int = 0,
    val height: Int = 0,
    val elements: List<ScreenElement> = emptyList(),
    val error: String = "",
)

@JsonClass(generateAdapter = true)
data class ScreenResponse(
    val ok: Boolean,
    val action: String = "",
    val detail: String = "",
    val error: String = "",
)

private data class Candidate(val id: String, val node: AccessibilityNodeInfo, val element: ScreenElement)

class ScreenAutomationService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private val moshi = Moshi.Builder().build()
    private val requestAdapter = moshi.adapter(ScreenRequest::class.java)
    private val observationAdapter = moshi.adapter(ScreenObservation::class.java)
    private val responseAdapter = moshi.adapter(ScreenResponse::class.java)

    override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenControlUiState.update(enabled = true, stage = ScreenStage.IDLE, message = "Accessibility Service พร้อม")
        startBridge()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            ScreenControlUiState.update(enabled = true, stage = ScreenStage.IDLE, message = "หน้าจอพร้อมให้ควบคุม")
        }
    }

    override fun onInterrupt() {
        ScreenControlUiState.update(enabled = false, stage = ScreenStage.ERROR, message = "Accessibility ถูกขัดจังหวะ")
    }

    private fun startBridge() {
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                server = ServerSocket(18790, 8, InetAddress.getByName("127.0.0.1"))
                while (running.get()) {
                    val socket = server?.accept() ?: break
                    launch(Dispatchers.IO) { handle(socket) }
                }
            } catch (_: Exception) {
                ScreenControlUiState.update(enabled = false, stage = ScreenStage.ERROR, message = "Screen bridge หยุดทำงาน")
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use {
            try {
                val input = it.getInputStream().bufferedReader()
                val first = input.readLine() ?: return
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
                val json = String(body, 0, read)
                when {
                    first.startsWith("GET /screen/observe") -> write(it, 200, observe())
                    first.startsWith("POST /screen/execute") -> {
                        val req = requestAdapter.fromJson(json) ?: ScreenRequest()
                        write(it, 200, execute(req))
                    }
                    else -> write(it, 404, ScreenResponse(false, error = "not found"))
                }
            } catch (e: Exception) {
                write(it, 500, ScreenResponse(false, error = e.message ?: "screen bridge error"))
            }
        }
    }

    private fun write(socket: Socket, code: Int, payload: Any) {
        val body = when (payload) {
            is ScreenObservation -> observationAdapter.toJson(payload)
            is ScreenResponse -> responseAdapter.toJson(payload)
            else -> "{}"
        }
        val out = socket.getOutputStream().bufferedWriter()
        out.write("HTTP/1.1 " + code + " " + if (code == 200) "OK" else "ERROR" + "\r\n")
        out.write("Content-Type: application/json\r\n")
        out.write("Content-Length: " + body.toByteArray().size + "\r\n")
        out.write("Connection: close\r\n\r\n")
        out.write(body)
        out.flush()
    }

    private fun observe(): ScreenObservation {
        ScreenControlUiState.update(stage = ScreenStage.SEEING, message = "กำลังอ่านหน้าจอ")
        val dm = resources.displayMetrics
        val root = rootInActiveWindow ?: return ScreenObservation(false, dm.widthPixels, dm.heightPixels, error = "no active window")
        val candidates = mutableListOf<Candidate>()
        collect(root, candidates)
        ScreenControlUiState.update(stage = ScreenStage.WAITING, message = "หน้าจอถูกอ่านแล้ว")
        return ScreenObservation(true, dm.widthPixels, dm.heightPixels, elements = candidates.map { it.element })
    }

    private fun execute(req: ScreenRequest): ScreenResponse {
        ScreenControlUiState.update(
            stage = ScreenStage.ACTING,
            action = req.action,
            target = if (req.target_id.isNotBlank()) req.target_id else if (req.x >= 0f) req.x.toString() + "," + req.y else "",
            message = "กำลังทำ " + req.action,
        )
        val result = when (req.action.lowercase()) {
            "tap", "click" -> tap(req)
            "long_press" -> longPress(req)
            "swipe", "drag" -> swipe(req)
            "back" -> global("back", GLOBAL_ACTION_BACK)
            "home" -> global("home", GLOBAL_ACTION_HOME)
            "wait" -> {
                Thread.sleep(req.duration_ms.coerceIn(50L, 5000L))
                ScreenResponse(true, "wait", "รอ " + req.duration_ms + " ms")
            }
            else -> ScreenResponse(false, req.action, error = "unsupported action: " + req.action)
        }
        ScreenControlUiState.update(
            stage = if (result.ok) ScreenStage.VERIFYING else ScreenStage.ERROR,
            action = result.action,
            message = if (result.ok) "รอตรวจผลจาก Main Agent" else result.error,
        )
        return result
    }

    private fun global(action: String, code: Int): ScreenResponse {
        val ok = performGlobalAction(code)
        return ScreenResponse(ok, action, if (ok) "สำเร็จ" else "", if (ok) "" else action + " failed")
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<Candidate>, path: String = "e") {
        if (out.size >= 40) return
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val label = when {
            text.isNotEmpty() -> text
            desc.isNotEmpty() -> desc
            node.isEditable -> "editable field"
            else -> ""
        }
        if (label.isNotEmpty() && (node.isClickable || node.isFocusable || node.isEditable)) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val id = path + out.size
            val role = if (node.isEditable) "editable" else if (node.isClickable) "button" else "view"
            out += Candidate(
                id,
                node,
                ScreenElement(
                    id = id,
                    label = label.take(120),
                    role = role,
                    clickable = node.isClickable,
                    editable = node.isEditable,
                    x = bounds.centerX().toFloat() / resources.displayMetrics.widthPixels,
                    y = bounds.centerY().toFloat() / resources.displayMetrics.heightPixels,
                    width = bounds.width().toFloat() / resources.displayMetrics.widthPixels,
                    height = bounds.height().toFloat() / resources.displayMetrics.heightPixels,
                ),
            )
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it, out, path + i + "-") }
    }

    private fun findTarget(id: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val candidates = mutableListOf<Candidate>()
        collect(root, candidates)
        return candidates.firstOrNull { it.id == id }?.node
    }

    private fun tap(req: ScreenRequest): ScreenResponse {
        val node = if (req.target_id.isNotBlank()) findTarget(req.target_id) else null
        if (node != null) {
            var n: AccessibilityNodeInfo? = node
            while (n != null) {
                if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return ScreenResponse(true, "tap", "กด " + req.target_id)
                }
                n = n.parent
            }
        }
        return gestureAt(req.x, req.y, "tap", 80)
    }

    private fun longPress(req: ScreenRequest): ScreenResponse =
        gestureAt(req.x, req.y, "long_press", req.duration_ms.coerceIn(500L, 5000L))

    private fun swipe(req: ScreenRequest): ScreenResponse {
        if (req.x < 0f || req.y < 0f || req.x2 < 0f || req.y2 < 0f) {
            return ScreenResponse(false, "swipe", error = "x,y,x2,y2 are required")
        }
        val dm = resources.displayMetrics
        val path = Path().apply {
            moveTo(req.x.coerceIn(0f, 1f) * dm.widthPixels, req.y.coerceIn(0f, 1f) * dm.heightPixels)
            lineTo(req.x2.coerceIn(0f, 1f) * dm.widthPixels, req.y2.coerceIn(0f, 1f) * dm.heightPixels)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, req.duration_ms.coerceIn(80L, 5000L))
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        return ScreenResponse(ok, "swipe", if (ok) "ลากสำเร็จ" else "", if (ok) "" else "gesture failed")
    }

    private fun gestureAt(x: Float, y: Float, action: String, duration: Long): ScreenResponse {
        if (x < 0f || y < 0f) return ScreenResponse(false, action, error = "x and y are required")
        val dm = resources.displayMetrics
        val px = x.coerceIn(0f, 1f) * dm.widthPixels
        val py = y.coerceIn(0f, 1f) * dm.heightPixels
        val path = Path().apply { moveTo(px, py) }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        return ScreenResponse(ok, action, if (ok) "ตำแหน่ง " + (x * 100f).format() + "%, " + (y * 100f).format() + "%" else "", if (ok) "" else "gesture failed")
    }

    private fun Float.format(): String = "%.1f".format(java.util.Locale.US, this)

    override fun onDestroy() {
        running.set(false)
        server?.close()
        ScreenControlUiState.update(enabled = false, stage = ScreenStage.IDLE, message = "Accessibility Service ปิดอยู่")
        super.onDestroy()
    }
}
