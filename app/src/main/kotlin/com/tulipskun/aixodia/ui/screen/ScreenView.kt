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
    /** True while one finger is moving the picture rather than the far pointer. */
    private var panning = false
    private var downPanX = 0f
    private var downPanY = 0f

    // Pinch state, captured when the second finger lands.
    private var pinchStartDistance = 0f
    private var pinchStartZoom = 1f
    private var pinchFocusX = 0f
    private var pinchFocusY = 0f
    private var pinchStartPanX = 0f
    private var pinchStartPanY = 0f

    // A double tap is two taps inside this window, near the same point.
    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

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
        if (surface?.blank != false) return
        val picture = surface?.bitmap() ?: return
        if (picture.width == 0 || picture.height == 0) return
        val box = pictureRect(picture.width, picture.height)
        canvas.drawBitmap(picture, null, box, draw)
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

    /** Whether the picture is bigger than the view, so a drag should pan it. */
    private fun canPan(): Boolean {
        val picture = surface?.bitmap() ?: return false
        val box = pictureRect(picture.width, picture.height)
        return box.width() > width + 1f || box.height() > height + 1f
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
                fingertips = event.pointerCount
                downAt = System.currentTimeMillis()
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                dragging = false
                movedEnough = false
                scrollFrom = event.y
                // Zoomed in, a drag pans the picture. The baseline is taken now
                // so the pan is measured from where the finger landed.
                panning = canPan()
                downPanX = panX
                downPanY = panY

                // Holding still is the right-click: arm it now, cancel it the
                // moment the finger moves or lifts.
                val press = Runnable {
                    if (movedEnough) return@Runnable
                    val (px, py) = toScreen(lastX, lastY)
                    socket.down(px, py, "right")
                    socket.up(px, py, "right")
                }
                longPress = press
                postDelayed(press, LONG_PRESS_MS)
                val (cx, cy) = toScreen(event.x, event.y)
                socket.move(cx, cy)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                fingertips = event.pointerCount
                // A second finger means the tap that was starting is not one.
                dropLongPress()
                if (fingertips >= 2) {
                    pinchStartDistance = distance(event)
                    pinchStartZoom = zoom
                    pinchFocusX = focusX(event)
                    pinchFocusY = focusY(event)
                    pinchStartPanX = panX
                    pinchStartPanY = panY
                    // Two fingers that only move together scroll; two that
                    // spread zoom. Which one it is is decided on the first move.
                    scrollFrom = event.getY(1)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (fingertips >= 2) {
                    val started = distance(event)
                    if (pinchStartDistance > 0f && started > 0f) {
                        val ratio = started / pinchStartDistance
                        val wanted = (pinchStartZoom * ratio).coerceIn(MIN_ZOOM, MAX_ZOOM)
                        if (abs(wanted - zoom) > 0.01f) {
                            applyZoom(wanted, pinchFocusX, pinchFocusY, pinchStartPanX, pinchStartPanY)
                            scrollFrom = event.getY(1)
                            return true
                        }
                    }
                    // Not a pinch: two fingers moving together scroll the page.
                    val y = event.getY(1)
                    val step = (y - scrollFrom).toInt()
                    if (abs(step) > SCROLL_STEP_PX && !canPan()) {
                        val (px, py) = toScreen(event.getX(1), y)
                        socket.wheel(px, py, if (step > 0) 1 else -1)
                        scrollFrom = y
                    }
                    return true
                }

                if (abs(event.x - downX) > TOUCH_SLOP || abs(event.y - downY) > TOUCH_SLOP) {
                    movedEnough = true
                    dropLongPress()
                }
                if (movedEnough) {
                    if (panning) {
                        // Zoomed in: a finger moves the picture, the way a photo
                        // viewer does, instead of dragging the far pointer.
                        panX = downPanX + (event.x - downX)
                        panY = downPanY + (event.y - downY)
                        clampPan()
                        invalidate()
                    } else {
                        val (px, py) = toScreen(event.x, event.y)
                        val (lx, ly) = toScreen(lastX, lastY)
                        if (!dragging) {
                            // The first movement after the slop becomes a real
                            // drag: press where the finger landed, then follow it.
                            dragging = true
                            socket.down(lx, ly, "left")
                        }
                        socket.move(px, py)
                    }
                }
                lastX = event.x
                lastY = event.y
                return true
            }

            MotionEvent.ACTION_UP -> {
                dropLongPress()
                if (fingertips < 2) {
                    val (px, py) = toScreen(event.x, event.y)
                    if (dragging) {
                        socket.up(px, py, "left")
                    } else if (!movedEnough) {
                        val now = System.currentTimeMillis()
                        val nearLast = abs(event.x - lastTapX) < DOUBLE_TAP_SLOP &&
                            abs(event.y - lastTapY) < DOUBLE_TAP_SLOP
                        if (now - lastTapAt < DOUBLE_TAP_MS && nearLast) {
                            // Two taps: fit the picture, or go back to actual
                            // size if it already fits. Never a click, so a
                            // double-click on the far side is not accidental.
                            toggleZoom()
                            lastTapAt = 0L
                        } else {
                            lastTapAt = now
                            lastTapX = event.x
                            lastTapY = event.y
                            // A tap is a click, but only once it is sure it is
                            // not the first half of a double tap.
                            postDelayed({ socket.down(px, py, "left"); socket.up(px, py, "left") }, TAP_GRACE_MS)
                        }
                    }
                }
                dragging = false
                movedEnough = false
                panning = false
                fingertips = 0
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
                panning = false
                fingertips = 0
                return true
            }
        }
        return super.onTouchEvent(event)
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

    /** Fits the picture to the view, or shows it at twice the fit when it fits. */
    private fun toggleZoom() {
        if (zoom > 1.05f) {
            zoom = 1f
            panX = 0f
            panY = 0f
        } else {
            zoom = FIT_AGAIN_ZOOM
            // Zoom around the middle of the view, which is where the eye is.
            panX = 0f
            panY = 0f
        }
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

        /** Where a double tap lands when the picture fitted the screen. */
        private const val FIT_AGAIN_ZOOM = 2f

        /** A double tap is two taps inside this many milliseconds… */
        private const val DOUBLE_TAP_MS = 280L
        /** …and this many pixels of each other. */
        private const val DOUBLE_TAP_SLOP = 48f

        /**
         * A tap waits this long before it becomes a click, so that the second
         * half of a double tap is not sent as a click first.
         */
        private const val TAP_GRACE_MS = 160L
    }
}
