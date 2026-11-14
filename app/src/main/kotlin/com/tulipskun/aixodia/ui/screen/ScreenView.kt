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
 * A frame that arrives before the first is what fixes the size, which is why
 * [sizeTo] is told the dimensions by the `hello` rather than guessing.
 */
class ScreenSurface {
    @Volatile
    private var frame: Bitmap? = null
    private val paint = Paint().apply { isFilterBitmap = true }

    fun sizeTo(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val current = frame
        if (current != null && current.width == width && current.height == height) return
        frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            // A new size has nothing to keep: the whole screen is coming.
            it.eraseColor(Color.BLACK)
        }
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
    }

    fun bitmap(): Bitmap? = frame
}

/**
 * The view that draws the picture and turns a finger into a mouse.
 *
 * The gestures are the ones a remote-desktop viewer on a phone has taught
 * everyone: one finger taps and drags, two fingers scroll, holding without
 * moving asks for a right-click. They are translated into the *captured*
 * coordinates — the engine's own pixels — rather than the view's, so the engine
 * never has to know how big the phone is.
 */
@SuppressLint("ViewConstructor")
class ScreenView(context: Context) : View(context) {
    private var socket: ScreenSocket? = null
    private var surface: ScreenSurface? = null

    private val draw = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
        // The screen is not a photo: nearest-neighbour keeps text legible when
        // the phone shows a smaller picture than the engine captured.
        isFilterBitmap = false
    }
    private val background = Paint().apply { color = Color.BLACK }

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

    fun attach(socket: ScreenSocket, surface: ScreenSurface) {
        this.socket = socket
        this.surface = surface
    }

    /** Puts a fresh frame on screen. Called from the socket's collector. */
    fun invalidateFrame() {
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)
        val picture = surface?.bitmap() ?: return
        if (picture.width == 0 || picture.height == 0) return
        canvas.drawBitmap(picture, null, fit(picture.width, picture.height), draw)
    }

    /** The rectangle the picture occupies, letterboxed into the view. */
    private fun fit(pictureWidth: Int, pictureHeight: Int): RectF {
        if (pictureWidth <= 0 || pictureHeight <= 0 || width == 0 || height == 0) {
            return RectF(0f, 0f, width.toFloat(), height.toFloat())
        }
        val scale = min(width.toFloat() / pictureWidth, height.toFloat() / pictureHeight)
        val drawnWidth = pictureWidth * scale
        val drawnHeight = pictureHeight * scale
        val left = (width - drawnWidth) / 2f
        val top = (height - drawnHeight) / 2f
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
        val box = fit(picture.width, picture.height)
        if (box.width() <= 0f || box.height() <= 0f) return 0 to 0
        val scaleX = picture.width / box.width()
        val scaleY = picture.height / box.height()
        val px = ((x - box.left) * scaleX).roundToInt().coerceIn(0, picture.width - 1)
        val py = ((y - box.top) * scaleY).roundToInt().coerceIn(0, picture.height - 1)
        return px to py
    }

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

                // Holding still is the right-click: arm it now, cancel it the
                // moment the finger moves or lifts.
                val (sx, sy) = toScreen(event.x, event.y)
                val press = Runnable {
                    val (px, py) = toScreen(lastX, lastY)
                    socket.down(px, py, "left")
                    socket.up(px, py, "left")
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
                scrollFrom = event.getY(1)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (fingertips >= 2) {
                    val y = event.getY(1)
                    val step = (y - scrollFrom).toInt()
                    if (abs(step) > SCROLL_STEP_PX) {
                        val (px, py) = toScreen(event.getX(1), y)
                        // Down the screen scrolls the page the same way a wheel
                        // does: the picture moves up, the notch is positive.
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
                    val (px, py) = toScreen(event.x, event.y)
                    val (lx, ly) = toScreen(lastX, lastY)
                    if (!dragging) {
                        // The first movement after the slop becomes a real drag:
                        // press where the finger landed, then follow it.
                        dragging = true
                        socket.down(lx, ly, "left")
                    }
                    socket.move(px, py)
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
                        // A tap: down and up at the same point, which is what a
                        // click is and what every control on the far side wants.
                        socket.down(px, py, "left")
                        socket.up(px, py, "left")
                    }
                }
                dragging = false
                movedEnough = false
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
                fingertips = 0
                return true
            }
        }
        return super.onTouchEvent(event)
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
    }
}
