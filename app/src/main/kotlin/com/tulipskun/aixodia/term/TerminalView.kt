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
    private var mouseButtonDown = false

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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resizeToView()
    }

    private fun resizeToView() {
        val em = emulator ?: return
        if (cellWidth <= 0f || cellHeight <= 0f || width == 0 || height == 0) return
        val cols = (width / cellWidth).toInt().coerceAtLeast(1)
        val rows = (height / cellHeight).toInt().coerceAtLeast(1)
        if (cols == em.columns && rows == em.rows) return
        em.resize(cols, rows)
        onResize?.invoke(cols, rows)
        if (pinnedToBottom) scrollTopPx = maxScroll().toFloat()
    }

    private fun contentRows(): Int = (emulator?.buffer?.scrollback?.size ?: 0) + (emulator?.rows ?: 0)
    private fun contentHeight(): Int = (contentRows() * cellHeight).toInt()
    private fun maxScroll(): Int = (contentHeight() - height).coerceAtLeast(0)

    private fun clampScroll() {
        scrollTopPx = scrollTopPx.coerceIn(0f, maxScroll().toFloat())
        val atBottom = scrollTopPx >= maxScroll() - 0.5f
        if (atBottom != pinnedToBottom) {
            pinnedToBottom = atBottom
            onScrollChanged?.invoke(atBottom)
        } else {
            pinnedToBottom = atBottom
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
        if (pinnedToBottom) scrollTopPx = maxScroll().toFloat()
        invalidate()
    }

    fun scrollToBottom() {
        scrollTopPx = maxScroll().toFloat()
        pinnedToBottom = true
        invalidate()
    }

    fun pageUp() {
        scrollTopPx -= (height * 0.9f)
        clampScroll()
        invalidate()
    }

    fun pageDown() {
        scrollTopPx += (height * 0.9f)
        clampScroll()
        invalidate()
    }

    // =======================================================================
    // drawing
    // =======================================================================

    override fun onDraw(canvas: Canvas) {
        val em = emulator ?: return
        canvas.drawColor(TerminalColors.DEFAULT_BG)
        val buffer = em.buffer
        val scrollback = buffer.scrollback
        val firstIndex = (scrollTopPx / cellHeight).toInt()
        val yShift = (scrollTopPx - firstIndex * cellHeight)
        val visibleRows = (height / cellHeight).toInt() + 2

        for (v in 0 until visibleRows) {
            val contentIndex = firstIndex + v
            val row = rowAt(scrollback, buffer, contentIndex) ?: continue
            val top = v * cellHeight - yShift
            drawRow(canvas, row, top, contentIndex)
        }

        // The cursor belongs to the live screen; hide it while scrolled back.
        if (pinnedToBottom && em.cursorVisible && hasFocus()) {
            val cursorTop = (scrollback.size + buffer.cursorRow) * cellHeight - scrollTopPx
            drawCursor(canvas, em, buffer, cursorTop)
        }
    }

    private fun rowAt(
        scrollback: List<TerminalRow>,
        buffer: TerminalBuffer,
        index: Int,
    ): TerminalRow? {
        if (index < 0) return null
        if (index < scrollback.size) return scrollback[index]
        val lineIndex = index - scrollback.size
        return if (lineIndex < buffer.lines.size) buffer.lines[lineIndex] else null
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
                mouseButtonDown = false
                velocityTracker.clear()
                velocityTracker.addMovement(event)
                if (em.mouseReporting() && event.pointerCount == 1) {
                    sendMouse(em, event, button = 0, release = false)
                    mouseButtonDown = true
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger always means "scroll", even when the program
                // wants the mouse for one finger.
                dragging = true
                lastTouchY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker.addMovement(event)
                if (em.mouseReporting() && event.pointerCount == 1 && mouseButtonDown) {
                    if (em.mouseTracking >= 3) sendMouse(em, event, button = 0, release = false)
                    return true
                }
                val dy = lastTouchY - event.y
                if (kotlin.math.abs(event.y - downY) > touchSlop) dragging = true
                lastTouchY = event.y
                if (dragging) {
                    scrollTopPx += dy
                    clampScroll()
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                velocityTracker.addMovement(event)
                if (em.mouseReporting() && event.pointerCount == 1 && mouseButtonDown) {
                    sendMouse(em, event, button = 0, release = true)
                    mouseButtonDown = false
                    return true
                }
                val quick = event.eventTime - downTime < 200
                if (dragging) {
                    velocityTracker.computeCurrentVelocity(1000)
                    val vy = -velocityTracker.yVelocity
                    scroller.fling(
                        0, scrollTopPx.toInt(), 0, vy.toInt(),
                        0, 0, 0, maxScroll().coerceAtLeast(0),
                    )
                    postInvalidateOnAnimation()
                } else if (quick && !em.mouseReporting()) {
                    // A tap on a terminal with no mouse mode is how you ask for
                    // the keyboard; nothing is sent to the shell.
                    showSoftKeyboard()
                    onUserInteraction?.invoke()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                mouseButtonDown = false
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun sendMouse(em: TerminalEmulator, event: MotionEvent, button: Int, release: Boolean) {
        val col = (event.x / cellWidth).toInt().coerceIn(0, em.columns - 1)
        val row = (event.y / cellHeight).toInt().coerceIn(0, em.rows - 1)
        em.mouseReport(button, col, row, release)?.let { inputSink?.invoke(it) }
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
