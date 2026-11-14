package com.tulipskun.aixodia.ui.screen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.tulipskun.aixodia.data.remote.ScreenSocket
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import android.graphics.Path

/**
 * The picture, kept as the last bitmap that arrived.
 *
 * Tiles are painted straight onto one bitmap the size of the captured screen, so
 * a repaint is one draw call and a tile costs one `drawBitmap` at its offset. The
 * mosaic is never rebuilt from scratch: the engine only sends what changed, and
 * what did not change is still here.
 *
 * [blank] is the case the engine cannot help with: no frame has arrived yet, or
 * the link dropped before one did. Drawing black and drawing "no picture" look
 * identical on a phone, so the view is told which it is and says so.
 */
class ScreenSurface {
    @Volatile
    private var frame: Bitmap? = null
    private val paint = Paint().apply { isFilterBitmap = true }

    /** True until a frame has painted something. */
    @Volatile
    var blank: Boolean = true
        private set

    fun sizeTo(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val current = frame
        if (current != null && current.width == width && current.height == height) return
        frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            // A new size has nothing to keep: the whole screen is coming.
            it.eraseColor(Color.BLACK)
        }
        blank = true
    }

    /** Paints one frame's changed tiles into the picture. */
    fun paint(patch: ScreenSocket.Event.Patch) {
        val target = frame ?: run {
            sizeTo(patch.width, patch.height)
            frame
        } ?: return
        val canvas = Canvas(target)
        for (tile in patch.patches) {
            val left = (tile.col * tile.bitmap.width).toFloat()
            val top = (tile.row * tile.bitmap.height).toFloat()
            // The tile size is the grid's, so a clipped edge tile lands correctly.
            val destination = RectF(left, top, left + tile.bitmap.width, top + tile.bitmap.height)
            canvas.drawBitmap(tile.bitmap, null, destination, paint)
        }
        if (patch.patches.isNotEmpty()) blank = false
    }

    /**
     * Where the machine's pointer is drawn, in captured pixels, and the last
     * place the local finger left it.
     *
     * A remote desktop without a visible pointer is a guess: you move your
     * finger and hope the machine agrees. This is the arrow, drawn here.
     */
    @Volatile
    var cursor: Pair<Int, Int>? = null
        private set

    fun cursorTo(at: Pair<Int, Int>?) {
        cursor = at
    }

    /** Forgets the picture, for a link that has gone away. */
    fun clear() {
        frame = null
        blank = true
    }

    fun bitmap(): Bitmap? = frame
}

/**
 * The view that draws the picture and turns a finger into a mouse.
 *
 * Zoom and pan work the way a phone's image viewer does, and the way the VNC
 * viewer everyone knows does: one finger drags the pointer when the picture fits
 * the screen, and pans the picture when it does not; two fingers pinch to zoom;
 * a double tap fits the picture or returns to actual size. Everything is
 * anchored on the point between the fingers, so a pinch grows the part being
 * looked at rather than the middle of the screen.
 *
 * The gestures a remote desktop needs and a photo viewer does not are kept on
 * their own: a tap is a click, a held press is the right button, and two fingers
 * that move together scroll instead of zooming.
 */
@SuppressLint("ViewConstructor")
class ScreenView(context: Context) : View(context) {
    private var socket: ScreenSocket? = null
    private var surface: ScreenSurface? = null

    private val draw = Paint().apply {
        isAntiAlias = true
        // The screen is not a photo: nearest-neighbour keeps text legible when
        // the phone shows a smaller picture than the engine captured.
        isFilterBitmap = false
    }
    private val background = Paint().apply { color = Color.BLACK }
    private val cursorEdge = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.BLACK
    }
    private val cursorFill = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    /** 1 means the picture exactly fits the view. */
    private var zoom = 1f
    /** Offset of the picture within the view, in view pixels, after the fit. */
    private var panX = 0f
    private var panY = 0f

    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var movedEnough = false
    private var fingertips = 0
    private var scrollFrom = 0f
    private var longPress: Runnable? = null
    /** True once a second finger has turned the gesture into picture handling. */
    private var twoFinger = false
    /** Where two fingers started, so their movement can be measured. */
    private var twoFingerX = 0f
    private var twoFingerY = 0f

    /**
     * How a finger drives the far machine.
     *
     * [TouchMode.Trackpad] treats the screen as a trackpad: the pointer moves
     * with the finger and stays where it is let go, which is how a careful click
     * is made. [TouchMode.Direct] puts the pointer exactly under the finger,
     * which is quicker for pressing something visible but hides the target
     * under the fingertip. A viewer usually wants one and switches to the other
     * for a particular job, so both are offered rather than one being chosen for
     * everybody.
     */
    enum class TouchMode { Trackpad, Direct }

    var touchMode: TouchMode = TouchMode.Trackpad

    /**
     * True when the picture is a decoded video underneath this view rather than
     * a bitmap drawn by it.
     *
     * The view still owns the fingers and still draws the arrow; it only stops
     * painting a picture it does not have.
     */
    var videoMode: Boolean = false

    /** Where the pointer is, in picture pixels, while a trackpad finger works. */
    private var pointerX = 0
    private var pointerY = 0
    /** The finger's last place, so its travel can be measured. */
    private var lastFinger: Pair<Int, Int>? = null

    // Pinch state, captured when the second finger lands.
    private var pinchStartDistance = 0f
    private var pinchStartZoom = 1f
    private var pinchFocusX = 0f
    private var pinchFocusY = 0f
    private var pinchStartPanX = 0f
    private var pinchStartPanY = 0f


    fun attach(socket: ScreenSocket, surface: ScreenSurface) {
        this.socket = socket
        this.surface = surface
    }

    /** Puts a fresh frame on screen. Called from the socket's collector. */
    fun invalidateFrame() {
        postInvalidateOnAnimation()
    }

    /** Drops the picture and goes back to "nothing yet". */
    fun cleared() {
        zoom = 1f
        panX = 0f
        panY = 0f
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)
        if (videoMode) {
            // The picture is on the video surface beneath; only the arrow and
            // the gestures belong to this view.
            val picture = surface?.bitmap()
            if (picture != null) {
                drawCursor(canvas, picture.width, picture.height, pictureRect(picture.width, picture.height))
            }
            return
        }
        if (surface?.blank != false) return
        val picture = surface?.bitmap() ?: return
        if (picture.width == 0 || picture.height == 0) return
        val box = pictureRect(picture.width, picture.height)
        canvas.drawBitmap(picture, null, box, draw)
        drawCursor(canvas, picture.width, picture.height, box)
    }

    /**
     * The arrow, at the pointer's place in the picture, scaled with it.
     *
     * Drawn after the tiles so it is never buried, and with a dark outline under
     * a light fill so it stays visible over both a white document and a black
     * terminal.
     */
    private fun drawCursor(canvas: Canvas, pictureWidth: Int, pictureHeight: Int, box: RectF) {
        val at = surface?.cursor ?: return
        if (pictureWidth <= 0 || pictureHeight <= 0 || box.width() <= 0f) return
        val scaleX = box.width() / pictureWidth
        val scaleY = box.height() / pictureHeight
        val x = box.left + at.first * scaleX
        val y = box.top + at.second * scaleY
        // A pointer that shrinks to nothing on a fitted picture is unreadable;
        // it keeps a floor size of its own, as the desktop viewers do.
        val size = (CURSOR_SIZE * max(1f, min(scaleX, scaleY))).coerceAtMost(CURSOR_MAX)
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + size * 0.62f, y + size)
            lineTo(x + size * 0.28f, y + size * 0.76f)
            lineTo(x + size * 0.08f, y + size * 1.12f)
            lineTo(x - size * 0.10f, y + size * 1.02f)
            lineTo(x + size * 0.10f, y + size * 0.66f)
            lineTo(x - size * 0.16f, y + size * 0.66f)
            close()
        }
        canvas.drawPath(path, cursorEdge)
        canvas.drawPath(path, cursorFill)
    }

    /** Where the picture is drawn, after fit, zoom and pan. */
    private fun pictureRect(pictureWidth: Int, pictureHeight: Int): RectF {
        if (pictureWidth <= 0 || pictureHeight <= 0 || width == 0 || height == 0) {
            return RectF(0f, 0f, width.toFloat(), height.toFloat())
        }
        val fitScale = min(width.toFloat() / pictureWidth, height.toFloat() / pictureHeight)
        val scale = fitScale * zoom
        val drawnWidth = pictureWidth * scale
        val drawnHeight = pictureHeight * scale
        val left = (width - drawnWidth) / 2f + panX
        val top = (height - drawnHeight) / 2f + panY
        return RectF(left, top, left + drawnWidth, top + drawnHeight)
    }

    /**
     * A point on the view, as a point on the captured screen.
     *
     * Outside the picture is clamped rather than dropped: a finger that leaves
     * the letterbox during a drag should keep dragging along the nearest edge,
     * the way a mouse at the edge of a screen does.
     */
    private fun toScreen(x: Float, y: Float): Pair<Int, Int> {
        val picture = surface?.bitmap() ?: return 0 to 0
        val box = pictureRect(picture.width, picture.height)
        if (box.width() <= 0f || box.height() <= 0f) return 0 to 0
        val scaleX = picture.width / box.width()
        val scaleY = picture.height / box.height()
        val px = ((x - box.left) * scaleX).roundToInt().coerceIn(0, picture.width - 1)
        val py = ((y - box.top) * scaleY).roundToInt().coerceIn(0, picture.height - 1)
        return px to py
    }

    /** Keeps the picture from being dragged off into empty space. */
    private fun clampPan() {
        val picture = surface?.bitmap() ?: return
        val box = pictureRect(picture.width, picture.height)
        // How far it may slide: half the overflow on each side, so an edge can
        // reach the middle of the view but not fly past it.
        val slackX = max(0f, (box.width() - width) / 2f)
        val slackY = max(0f, (box.height() - height) / 2f)
        panX = panX.coerceIn(-slackX, slackX)
        panY = panY.coerceIn(-slackY, slackY)
    }

    private fun distance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return kotlin.math.hypot(dx, dy)
    }

    private fun focusX(event: MotionEvent): Float =
        if (event.pointerCount < 2) event.x else (event.getX(0) + event.getX(1)) / 2f

    private fun focusY(event: MotionEvent): Float =
        if (event.pointerCount < 2) event.y else (event.getY(0) + event.getY(1)) / 2f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val socket = socket ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fingertips = 1
                downAt = System.currentTimeMillis()
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                dragging = false
                movedEnough = false
                twoFinger = false

                val press = Runnable {
                    if (movedEnough) return@Runnable
                    val (px, py) = if (touchMode == TouchMode.Direct) {
                        toScreen(lastX, lastY)
                    } else {
                        pointerX to pointerY
                    }
                    socket.down(px, py, "right")
                    socket.up(px, py, "right")
                }
                longPress = press
                postDelayed(press, LONG_PRESS_MS)

                // The pointer follows the finger from the first moment, exactly
                // as a remote desktop's does: there is no separate "grab" step.
                val (cx, cy) = toScreen(event.x, event.y)
                if (touchMode == TouchMode.Direct) {
                    socket.move(cx, cy)
                    markCursor(cx, cy)
                } else {
                    // Trackpad: the pointer keeps its place and only the finger's
                    // travel is added to it, so lifting and starting again does
                    // not make the pointer jump. That is the whole point of the
                    // mode — a precise click needs a pointer that stays put.
                    val origin = surface?.cursor ?: (cx to cy)
                    pointerX = origin.first
                    pointerY = origin.second
                    lastFinger = cx to cy
                }
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                fingertips = event.pointerCount
                dropLongPress()
                if (fingertips >= 2) {
                    // A second finger switches the whole gesture to the picture:
                    // from here nothing is sent to the machine until both lift.
                    if (dragging) {
                        val (px, py) = toScreen(lastX, lastY)
                        socket.up(px, py, "left")
                        dragging = false
                    }
                    twoFinger = true
                    pinchStartDistance = distance(event)
                    pinchStartZoom = zoom
                    pinchFocusX = focusX(event)
                    pinchFocusY = focusY(event)
                    pinchStartPanX = panX
                    pinchStartPanY = panY
                    twoFingerX = focusX(event)
                    twoFingerY = focusY(event)
                    scrollFrom = event.getY(1)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (twoFinger) {
                    // Two fingers: pinch to zoom, or move together to pan.
                    val apart = distance(event)
                    if (pinchStartDistance > 0f && apart > 0f) {
                        val ratio = apart / pinchStartDistance
                        val wanted = (pinchStartZoom * ratio).coerceIn(MIN_ZOOM, MAX_ZOOM)
                        if (abs(wanted - zoom) > 0.01f) {
                            applyZoom(wanted, pinchFocusX, pinchFocusY, pinchStartPanX, pinchStartPanY)
                            return true
                        }
                    }
                    // Both fingers moved together: that is a pan, the way a
                    // photo viewer treats it, not a scroll to the machine. A
                    // remote desktop sends wheel notches only when the picture
                    // already fits the screen, otherwise the picture itself is
                    // what wants to move.
                    val dx = focusX(event) - twoFingerX
                    val dy = focusY(event) - twoFingerY
                    if (abs(dx) > 0.5f || abs(dy) > 0.5f) {
                        if (zoom > 1.01f) {
                            panX = pinchStartPanX + dx
                            panY = pinchStartPanY + dy
                            clampPan()
                            invalidate()
                        } else {
                            val step = (event.getY(1) - scrollFrom).toInt()
                            if (abs(step) > SCROLL_STEP_PX) {
                                val (px, py) = toScreen(event.getX(1), event.getY(1))
                                socket.wheel(px, py, if (step > 0) 1 else -1)
                                scrollFrom = event.getY(1)
                            }
                        }
                    }
                    return true
                }

                if (abs(event.x - downX) > TOUCH_SLOP || abs(event.y - downY) > TOUCH_SLOP) {
                    movedEnough = true
                    dropLongPress()
                }

                // One finger always drives the machine: move continuously, and
                // press only once the movement is a drag rather than a tap.
                val (fingerX, fingerY) = toScreen(event.x, event.y)
                val (px, py) = if (touchMode == TouchMode.Direct) {
                    fingerX to fingerY
                } else {
                    // Travel since the last event, added to where the pointer
                    // already is. The scale lifts a finger's movement to cover
                    // the whole screen, so a small gesture reaches the corners.
                    val (seenX, seenY) = lastFinger ?: (fingerX to fingerY)
                    val movedX = pointerX + ((fingerX - seenX) * TRACKPAD_GAIN).roundToInt()
                    val movedY = pointerY + ((fingerY - seenY) * TRACKPAD_GAIN).roundToInt()
                    val picture = surface?.bitmap()
                    pointerX = movedX.coerceIn(0, (picture?.width ?: 1) - 1)
                    pointerY = movedY.coerceIn(0, (picture?.height ?: 1) - 1)
                    pointerX to pointerY
                }
                lastFinger = fingerX to fingerY

                if (movedEnough && !dragging && touchMode == TouchMode.Direct) {
                    val (sx, sy) = toScreen(downX, downY)
                    dragging = true
                    socket.down(sx, sy, "left")
                } else if (movedEnough && !dragging) {
                    // In trackpad mode the press happens at the pointer, which
                    // is where the user is looking, not under the finger.
                    dragging = true
                    socket.down(px, py, "left")
                }
                socket.move(px, py)
                markCursor(px, py)
                lastX = event.x
                lastY = event.y
                return true
            }

            MotionEvent.ACTION_UP -> {
                dropLongPress()
                if (!twoFinger) {
                    // Where the finger is in the picture, or in trackpad mode
                    // where the pointer ended up — which is not the same place,
                    // and is the one the user is looking at.
                    val (px, py) = if (touchMode == TouchMode.Direct) {
                        toScreen(event.x, event.y)
                    } else {
                        pointerX to pointerY
                    }
                    if (dragging) {
                        socket.up(px, py, "left")
                    } else if (!movedEnough) {
                        // A tap is a click. It is sent after the grace window so
                        // that the first half of a double tap is not one.
                        postDelayed({ socket.down(px, py, "left"); socket.up(px, py, "left") }, TAP_GRACE_MS)
                    }
                    markCursor(px, py)
                }
                dragging = false
                movedEnough = false
                twoFinger = false
                fingertips = 0
                lastFinger = null
                return true
            }

            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP -> {
                dropLongPress()
                if (event.actionMasked == MotionEvent.ACTION_CANCEL && dragging) {
                    val (px, py) = toScreen(lastX, lastY)
                    socket.up(px, py, "left")
                }
                dragging = false
                movedEnough = false
                twoFinger = false
                fingertips = 0
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** Keeps the drawn arrow where the local finger just put it. */
    private fun markCursor(x: Int, y: Int) {
        surface?.cursorTo(x to y)
        postInvalidateOnAnimation()
    }

    /**
     * Sets the zoom, keeping the point under the fingers where it is.
     *
     * The picture is drawn centred plus `pan`; growing it by a factor while the
     * focus stays put means moving the centre by the same proportion, which is
     * what makes a pinch feel anchored rather than skating.
     */
    private fun applyZoom(wanted: Float, focusX: Float, focusY: Float, basePanX: Float, basePanY: Float) {
        val previous = zoom
        zoom = wanted.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val growth = if (previous <= 0f) 1f else zoom / previous
        // Distance of the focus from the view's centre, scaled by the growth.
        val centreX = width / 2f
        val centreY = height / 2f
        panX = basePanX + (focusX - centreX - basePanX) * (1 - growth)
        panY = basePanY + (focusY - centreY - basePanY) * (1 - growth)
        clampPan()
        invalidate()
    }

    private fun dropLongPress() {
        longPress?.let { removeCallbacks(it) }
        longPress = null
    }

    override fun onDetachedFromWindow() {
        dropLongPress()
        super.onDetachedFromWindow()
    }

    companion object {
        /** How long a finger must stay still before it counts as a right-click. */
        private const val LONG_PRESS_MS = 550L

        /** Movement in view pixels before a press becomes a drag. */
        private const val TOUCH_SLOP = 12f

        /** Movement before a two-finger drag becomes a scroll notch. */
        private const val SCROLL_STEP_PX = 24f

        /** How much the picture may grow; below 1 it would be a keyhole. */
        private const val MIN_ZOOM = 1f
        private const val MAX_ZOOM = 4f

        /**
         * How far the pointer travels for a finger's travel.
         *
         * A thumb can cross about half the screen comfortably, and the picture
         * is wider than that, so the finger's movement is amplified a little.
         */
        private const val TRACKPAD_GAIN = 2.2f

        /** The arrow's size at 1:1, and the largest it is allowed to grow. */
        private const val CURSOR_SIZE = 14f
        private const val CURSOR_MAX = 30f

        /**
         * A tap waits this long before it becomes a click, so that the second
         * half of a double tap is not sent as a click first.
         */
        private const val TAP_GRACE_MS = 160L
    }
}
