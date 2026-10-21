package com.tulipskun.aixodia.term

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.InputType
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller

/**
 * How long the layout has to hold still before the grid and the shell are
 * resized. The keyboard animates over several frames; resizing on each one
 * makes a full-screen program repaint repeatedly and moves lines in and out of
 * scrollback, which reads as a stuttering, half-drawn screen. Waiting for the
 * last frame sends one resize, at the size the user will actually type into.
 */
private const val RESIZE_SETTLE_MS = 140L

/**
 * Draws a [TerminalEmulator] and turns fingers and keys into bytes. Everything
 * here is the app's own: the grid is painted with a [Paint], the keyboard is a
 * plain [InputConnection], and a tap becomes a mouse click only when the
 * program on the far end has asked for the mouse.
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Where the bytes we produce go; the panel wires this to the socket. */
    var inputSink: ((ByteArray) -> Unit)? = null

    /** Called when the grid size changed, so the panel can tell the engine. */
    var onResize: ((cols: Int, rows: Int) -> Unit)? = null

    /** Called on a mouse click, so the panel can nudge the soft keyboard. */
    var onUserInteraction: (() -> Unit)? = null

    /** Called when the view is at (or leaves) the live bottom. */
    var onScrollChanged: ((atBottom: Boolean) -> Unit)? = null

    /** Called after a latched modifier is spent, so the key row can un-light. */
    var onModsConsumed: (() -> Unit)? = null

    // Latched by the Ctrl/Alt chips: they apply to the next thing typed, then
    // clear themselves, exactly as a one-shot modifier should.
    private var latchedCtrl = false
    private var latchedAlt = false

    private var emulator: TerminalEmulator? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        isSubpixelText = true
    }
    private val boldPaint = Paint(paint).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
    private val italicPaint = Paint(paint).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC) }
    private val boldItalicPaint = Paint(paint).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC)
    }
    private val bgPaint = Paint().apply { style = Paint.Style.FILL }
    private val cursorPaint = Paint().apply { style = Paint.Style.FILL }

    // One BMP glyph can be drawn without allocating a String per cell per
    // frame — which matters while a finger is flinging a screenful.
    private val glyph = CharArray(2)

    var fontSizeSp = 13f
        set(value) {
            if (field == value) return
            field = value
            updateMetrics()
            requestLayout()
            invalidate()
        }

    private var cellWidth = 1f
    private var cellHeight = 1f
    private var baseline = 0f

    private var scrollTopPx = 0f
    private var pinnedToBottom = true
    private val scroller = OverScroller(context)
    private val velocityTracker = VelocityTracker.obtain()
    private var lastTouchY = 0f
    private var downY = 0f
    private var downX = 0f
    private var downTime = 0L
    private var dragging = false
    private var wheelAccum = 0f

    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        cursorPaint.color = TerminalColors.DEFAULT_CURSOR
        updateMetrics()
    }

    private fun updateMetrics() {
        paint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics,
        )
        boldPaint.textSize = paint.textSize
        italicPaint.textSize = paint.textSize
        boldItalicPaint.textSize = paint.textSize
        // A monospace font is monospaced for every glyph, so one advance is the
        // cell width; the line box comes from the font metrics.
        cellWidth = paint.measureText("M").coerceAtLeast(1f)
        val fm = paint.fontMetrics
        cellHeight = (fm.descent - fm.ascent + fm.leading).coerceAtLeast(1f)
        baseline = -fm.ascent
    }

    fun setEmulator(emulator: TerminalEmulator) {
        this.emulator = emulator
        resizeToView()
        invalidate()
    }

    fun emulator(): TerminalEmulator? = emulator

    // =======================================================================
    // geometry
    // =======================================================================

    /**
     * The keyboard slides in over several frames and the view is re-measured
     * on each one. Telling the shell to resize on every frame makes a
     * full-screen program repaint its whole screen each time, so the reports
     * are rate-limited instead of sent raw. But waiting for the layout to stop
     * moving makes the keyboard feel like it is dragging the terminal behind
     * it, so the floor is one frame: a burst of size changes collapses to one
     * report per [RESIZE_THROTTLE_MS], and the last one lands as the layout
     * settles.
     */
    private val pendingResize = Runnable { resizeToView() }
    private var sizedAtLeastOnce = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        removeCallbacks(pendingResize)
        if (sizedAtLeastOnce) {
            // The keyboard is still moving. Let the layout settle first; the
            // old grid keeps drawing (clipped, if it is taller) until then.
            postDelayed(pendingResize, RESIZE_SETTLE_MS)
        } else {
            // The first measurement is not an animation: a terminal that waits
            // to tell the shell its real size starts at 80x24 and grows.
            sizedAtLeastOnce = true
            resizeToView()
        }
        // The canvas fills the new area immediately; the grid catches up.
        invalidate()
    }

    private fun resizeToView() {
        val em = emulator ?: return
        if (cellWidth <= 0f || cellHeight <= 0f || width == 0 || height == 0) return
        val cols = (width / cellWidth).toInt().coerceAtLeast(1)
        val rows = (height / cellHeight).toInt().coerceAtLeast(1)
        if (cols == em.columns && rows == em.rows) return
        em.resize(cols, rows)
        onResize?.invoke(cols, rows)
        scrollTopPx = 0f
    }


    private fun clampScroll() {
        scrollTopPx = 0f
        if (!pinnedToBottom) {
            pinnedToBottom = true
            onScrollChanged?.invoke(true)
        } else {
            pinnedToBottom = true
        }
    }

    /** Set the one-shot Ctrl/Alt chips; they are spent by the next keystroke. */
    fun setLatchedMods(ctrl: Boolean, alt: Boolean) {
        latchedCtrl = ctrl
        latchedAlt = alt
    }

    /**
     * Text produced by the keyboard. A latched Ctrl or Alt is applied to the
     * first character and then released, so Ctrl then c really does send 0x03.
     */
    fun sendTyped(text: String) {
        if (text.isEmpty()) return
        if (!latchedCtrl && !latchedAlt) {
            sendText(text)
            return
        }
        val ctrl = latchedCtrl
        val alt = latchedAlt
        latchedCtrl = false
        latchedAlt = false
        onModsConsumed?.invoke()
        val cp = text.codePointAt(0)
        val rest = text.substring(Character.charCount(cp))
        val sb = StringBuilder()
        if (alt) sb.append('\u001b')
        if (ctrl) {
            sb.append(controlFor(cp).toChar())
        } else {
            sb.appendCodePoint(cp)
        }
        sb.append(rest)
        sendText(sb.toString())
    }

    private fun controlFor(cp: Int): Int = when {
        cp in 'a'.code..'z'.code -> cp - 'a'.code + 1
        cp in 'A'.code..'Z'.code -> cp - 'A'.code + 1
        cp == ' '.code -> 0
        cp == '['.code -> 0x1B
        cp == '\\'.code -> 0x1C
        cp == ']'.code -> 0x1D
        cp == '^'.code -> 0x1E
        cp == '_'.code -> 0x1F
        else -> cp
    }

    /** Called by the panel whenever the emulator produced output. */
    fun onContentChanged() {
        // The view is a fixed page: the emulator's screen is the page, and
        // anything that scrolled off it is not drawn. Repaint only.
        invalidate()
    }

    fun scrollToBottom() {
        clampScroll()
        invalidate()
    }

    fun pageUp() {
        // Local scrollback is deliberately gone: a real terminal page never
        // pans behind the program's back. PgUp reaches the program as a key.
        inputSink?.invoke("\u001b[5~".toByteArray())
    }

    fun pageDown() {
        inputSink?.invoke("\u001b[6~".toByteArray())
    }

    // =======================================================================
    // drawing
    // =======================================================================

    override fun onDraw(canvas: Canvas) {
        val em = emulator ?: return
        canvas.drawColor(TerminalColors.DEFAULT_BG)
        val buffer = em.buffer
        // One fixed page: the emulator's current screen, top row at the top of
        // the view. The scrollback the buffer keeps is for reading history
        // elsewhere (and for correct reflow); it is never painted, so a
        // reconnect's replay cannot leave an old frame above the live one.
        for (v in 0 until buffer.lines.size) {
            val row = buffer.lines[v]
            drawRow(canvas, row, v * cellHeight, v)
        }

        if (em.cursorVisible && hasFocus()) {
            drawCursor(canvas, em, buffer, buffer.cursorRow * cellHeight)
        }
    }

    private fun drawRow(canvas: Canvas, row: TerminalRow, top: Float, contentIndex: Int) {
        val count = row.columns
        var col = 0
        // Backgrounds first, batched into runs of one colour.
        while (col < count) {
            val bg = row.bg[col]
            var end = col + 1
            while (end < count && row.bg[end] == bg) end++
            if (bg != TerminalColors.DEFAULT_BG) {
                bgPaint.color = bg
                canvas.drawRect(col * cellWidth, top, end * cellWidth, top + cellHeight, bgPaint)
            }
            col = end
        }

        val y = top + baseline
        col = 0
        while (col < count) {
            val cp = row.chars[col]
            val attr = row.flags[col].toInt()
            if (cp != 0 && cp != TerminalRow.SPACE && attr and TerminalAttr.INVISIBLE == 0) {
                val cellFg = if (attr and TerminalAttr.INVERSE != 0) row.bg[col] else row.fg[col]
                val cellBg = if (attr and TerminalAttr.INVERSE != 0) row.fg[col] else row.bg[col]
                if (attr and TerminalAttr.INVERSE != 0 && cellBg != TerminalColors.DEFAULT_BG) {
                    bgPaint.color = cellBg
                    canvas.drawRect(col * cellWidth, top, (col + row.width[col]) * cellWidth, top + cellHeight, bgPaint)
                }
                val p = paintFor(attr)
                p.color = applyDim(cellFg, attr)
                val x = col * cellWidth
                val combining = row.combiningAt(col)
                if (combining == null && cp < 0x10000) {
                    glyph[0] = cp.toChar()
                    canvas.drawText(glyph, 0, 1, x, y, p)
                } else {
                    val text = if (combining != null) {
                        String(Character.toChars(cp)) + combining
                    } else {
                        String(Character.toChars(cp))
                    }
                    canvas.drawText(text, x, y, p)
                }
                if (attr and TerminalAttr.UNDERLINE != 0) drawUnderline(canvas, x, top, row.width[col], p.color)
                if (attr and TerminalAttr.STRIKE != 0) drawStrike(canvas, x, top, row.width[col], p.color)
            }
            col += maxOf(1, row.width[col].toInt())
        }
    }

    private fun paintFor(attr: Int): Paint {
        val bold = attr and TerminalAttr.BOLD != 0
        val italic = attr and TerminalAttr.ITALIC != 0
        return when {
            bold && italic -> boldItalicPaint
            bold -> boldPaint
            italic -> italicPaint
            else -> paint
        }
    }

    private fun applyDim(color: Int, attr: Int): Int {
        if (attr and TerminalAttr.DIM == 0) return color
        val a = (color ushr 24) and 0xFF
        val r = ((color ushr 16) and 0xFF) * 6 / 10
        val g = ((color ushr 8) and 0xFF) * 6 / 10
        val b = (color and 0xFF) * 6 / 10
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun drawUnderline(canvas: Canvas, x: Float, top: Float, cells: Byte, color: Int) {
        bgPaint.color = color
        val y = top + cellHeight - 2f
        canvas.drawRect(x, y, x + cellWidth * cells, y + 1.5f, bgPaint)
    }

    private fun drawStrike(canvas: Canvas, x: Float, top: Float, cells: Byte, color: Int) {
        bgPaint.color = color
        val y = top + cellHeight / 2f
        canvas.drawRect(x, y, x + cellWidth * cells, y + 1.5f, bgPaint)
    }

    private fun drawCursor(canvas: Canvas, em: TerminalEmulator, buffer: TerminalBuffer, top: Float) {
        val x = buffer.cursorCol * cellWidth
        val row = buffer.lines[buffer.cursorRow]
        val wide = row.width.getOrElse(buffer.cursorCol) { 1 }.toInt().coerceAtLeast(1)
        cursorPaint.color = TerminalColors.DEFAULT_CURSOR
        when (em.cursorStyle) {
            3, 4 -> { // underline
                val y = top + cellHeight - 2f
                canvas.drawRect(x, y, x + cellWidth * wide, y + 2f, cursorPaint)
            }
            5, 6 -> { // bar
                canvas.drawRect(x, top, x + 2f, top + cellHeight, cursorPaint)
            }
            else -> { // block: draw behind, then the glyph in the background colour
                canvas.drawRect(x, top, x + cellWidth * wide, top + cellHeight, cursorPaint)
                val cp = row.chars.getOrElse(buffer.cursorCol) { 0 }
                if (cp != 0 && cp != TerminalRow.SPACE) {
                    paint.color = TerminalColors.DEFAULT_BG
                    val text = String(Character.toChars(cp)) + (row.combiningAt(buffer.cursorCol) ?: "")
                    canvas.drawText(text, x, top + baseline, paint)
                }
            }
        }
    }

    // =======================================================================
    // touch
    // =======================================================================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val em = emulator ?: return false
        // When the program wants the mouse — or is on the alternate screen, as
        // full-screen apps are — a drag is a wheel, not a scroll of our history.
        val wheelMode = em.mouseReporting() || em.alternateScreen || em.fullScreenDetected
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                onUserInteraction?.invoke()
                scroller.forceFinished(true)
                lastTouchY = event.y
                downY = event.y
                downX = event.x
                downTime = event.eventTime
                dragging = false
                wheelAccum = 0f
                velocityTracker.clear()
                velocityTracker.addMovement(event)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger is always a scroll.
                lastTouchY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker.addMovement(event)
                val dy = lastTouchY - event.y
                if (kotlin.math.abs(event.y - downY) > touchSlop) dragging = true
                lastTouchY = event.y
                if (wheelMode) {
                    wheelAccum += dy
                    dispatchWheel(em, event)
                }
                // Not a wheel: a terminal page does not pan through history.
                // The program owns the screen; drag does nothing.
                return true
            }
            MotionEvent.ACTION_UP -> {
                velocityTracker.addMovement(event)
                val quick = event.eventTime - downTime < 200
                if (wheelMode) {
                    // A tap is a click. A drag has already gone out as wheel
                    // notches, so it must not also fire a click.
                    // On the alternate screen with no mouse mode the program
                    // deliberately did not ask for clicks; only the mid-stream
                    // case is a guess.
                    val guessedMouse = em.fullScreenDetected && !em.alternateScreen
                    if (!dragging && quick && (em.mouseReporting() || guessedMouse)) {
                        sendMouse(em, event, button = 0, release = false)
                        sendMouse(em, event, button = 0, release = true)
                    }
                } else if (quick) {
                    showSoftKeyboard()
                    onUserInteraction?.invoke()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** Hands accumulated vertical motion to the program as wheel notches. */
    private fun dispatchWheel(em: TerminalEmulator, event: MotionEvent) {
        val step = (cellHeight * 2f).coerceAtLeast(1f)
        val col = (event.x / cellWidth).toInt().coerceIn(0, em.columns - 1)
        val row = (event.y / cellHeight).toInt().coerceIn(0, em.rows - 1)
        // Dragging up (dy > 0) means "show me later content", which is wheel
        // down; dragging down is wheel up.
        while (wheelAccum >= step) {
            sendWheel(em, 65, col, row)
            wheelAccum -= step
        }
        while (wheelAccum <= -step) {
            sendWheel(em, 64, col, row)
            wheelAccum += step
        }
    }

    private fun sendWheel(em: TerminalEmulator, button: Int, col: Int, row: Int) {
        if (em.mouseReporting()) {
            em.mouseReport(button, col, row, release = false)?.let { inputSink?.invoke(it) }
        } else if (em.alternateScreen) {
            // No mouse mode but a full-screen program: the terminal convention
            // is that the wheel arrives as an arrow key.
            val bytes = if (button == 65) "\u001b[B" else "\u001b[A"
            inputSink?.invoke(bytes.toByteArray(Charsets.US_ASCII))
        } else if (em.fullScreenDetected) {
            // The session was attached mid-stream and the mouse mode was in the
            // part of the output the engine no longer had. Modern full-screen
            // programs (opencode, the TUIs this terminal exists for) speak SGR,
            // and one that never wanted the mouse ignores it.
            inputSink?.invoke(em.wheelReport(button, col, row))
        }
    }

    private fun sendMouse(em: TerminalEmulator, event: MotionEvent, button: Int, release: Boolean) {
        val col = (event.x / cellWidth).toInt().coerceIn(0, em.columns - 1)
        val row = (event.y / cellHeight).toInt().coerceIn(0, em.rows - 1)
        val report = em.mouseReport(button, col, row, release)
            ?: if (em.fullScreenDetected) em.sgrReport(button, col, row, release) else null
        report?.let { inputSink?.invoke(it) }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTopPx = scroller.currY.toFloat()
            clampScroll()
            postInvalidateOnAnimation()
        }
    }

    // =======================================================================
    // keyboard
    // =======================================================================

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return TerminalInputConnection(this, false)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handleKey(event)) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun sendText(text: String) {
        if (text.isEmpty()) return
        inputSink?.invoke(text.toByteArray(Charsets.UTF_8))
    }

    /** Maps a key press to the bytes a terminal expects. */
    fun handleKey(event: KeyEvent): Boolean {
        val em = emulator
        val ctrl = event.isCtrlPressed || latchedCtrl
        val alt = event.isAltPressed || latchedAlt
        var handled = true
        when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> sendText("\r")
            KeyEvent.KEYCODE_DEL -> sendText("\u007f")
            KeyEvent.KEYCODE_FORWARD_DEL -> sendText("\u001b[3~")
            KeyEvent.KEYCODE_TAB ->
                if (event.isShiftPressed) sendText("\u001b[Z") else sendText("\t")
            KeyEvent.KEYCODE_ESCAPE -> sendText("\u001b")
            KeyEvent.KEYCODE_DPAD_UP -> sendBytes(em?.cursorKey('A') ?: "\u001b[A".toByteArray())
            KeyEvent.KEYCODE_DPAD_DOWN -> sendBytes(em?.cursorKey('B') ?: "\u001b[B".toByteArray())
            KeyEvent.KEYCODE_DPAD_RIGHT -> sendBytes(em?.cursorKey('C') ?: "\u001b[C".toByteArray())
            KeyEvent.KEYCODE_DPAD_LEFT -> sendBytes(em?.cursorKey('D') ?: "\u001b[D".toByteArray())
            KeyEvent.KEYCODE_MOVE_HOME -> sendText("\u001b[H")
            KeyEvent.KEYCODE_MOVE_END -> sendText("\u001b[F")
            KeyEvent.KEYCODE_PAGE_UP -> sendText("\u001b[5~")
            KeyEvent.KEYCODE_PAGE_DOWN -> sendText("\u001b[6~")
            KeyEvent.KEYCODE_INSERT -> sendText("\u001b[2~")
            else -> handled = false
        }
        if (handled) {
            consumeLatch()
            return true
        }

        val unicode = event.unicodeChar
        if (unicode == 0) return false
        val sb = StringBuilder()
        if (alt) sb.append('\u001b')
        if (ctrl) {
            sb.append(controlFor(unicode).toChar())
        } else {
            sb.appendCodePoint(unicode)
        }
        sendText(sb.toString())
        consumeLatch()
        return true
    }

    private fun consumeLatch() {
        if (latchedCtrl || latchedAlt) {
            latchedCtrl = false
            latchedAlt = false
            onModsConsumed?.invoke()
        }
    }

    private fun sendBytes(bytes: ByteArray) {
        inputSink?.invoke(bytes)
    }

    fun sendRaw(text: String) = sendText(text)

    fun showSoftKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        if (!isFocused) requestFocus()
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideSoftKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(pendingResize)
        velocityTracker.recycle()
    }

    /** A plain bridge: IME edits and hardware keys both become terminal bytes. */
    private class TerminalInputConnection(
        private val view: TerminalView,
        fullEditor: Boolean,
    ) : BaseInputConnection(view, fullEditor) {

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val s = text?.toString().orEmpty()
            // Enter arrives as a newline from most keyboards; a terminal wants CR.
            view.sendTyped(if (s == "\n") "\r" else s)
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength) { view.sendRaw("\u007f") }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) return view.handleKey(event)
            return true
        }
    }
}
