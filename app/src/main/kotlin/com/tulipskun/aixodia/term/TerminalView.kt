package com.tulipskun.aixodia.term

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.InputType
import android.util.AttributeSet
import android.util.TypedValue
import android.view.ActionMode
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import com.tulipskun.aixodia.R
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
 * How long after a tap on the chrome to ask for the keyboard again. Moving
 * focus off a text editor makes the system hide the IME on its own; asking
 * once immediately can race that hide, so the request is repeated a moment
 * later, once the focus change has settled.
 */
private const val KEYBOARD_RESTORE_MS = 120L

private const val MENU_COPY = 1
private const val MENU_SELECT_ALL = 2

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

    /**
     * Called when text selection starts or ends. While a selection is live the
     * drawer must stop watching horizontal drags, or dragging a selection
     * handle would slide the menu open instead.
     */
    var onSelectionChanged: ((selecting: Boolean) -> Unit)? = null

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

    // A whole run of plain cells is one drawText call instead of one per cell.
    // Reused so repainting does not allocate a String for every row.
    private var glyphRun = CharArray(256)

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

    // Text selection: a long press anchors a selection and raises a floating
    // action bar with Copy. The anchor (where the gesture began, usually the
    // start of a word) stays put while the cursor follows the finger.
    private val selectionPaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0x664A90D9
    }
    private var selecting = false
    private var selectingDrag = false
    private var selAnchorRow = 0
    private var selAnchorCol = 0
    private var selCursorRow = 0
    private var selCursorCol = 0
    private var selectionMode: ActionMode? = null

    private val longPress = Runnable { beginSelection(downX, downY) }

    private val restoreKeyboard = Runnable {
        if (!isAttachedToWindow) return@Runnable
        if (!isFocused) requestFocus()
        showSoftKeyboard()
    }

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
        // cell width; the line box comes from the font metrics. Bold and italic
        // faces can have a taller ascent than the regular face. Measuring only
        // the regular one made the top of a bold first row (the TUI title, for
        // example) rise above y=0, where the view clipped it.
        cellWidth = paint.measureText("M").coerceAtLeast(1f)
        val metrics = listOf(paint, boldPaint, italicPaint, boldItalicPaint)
            .map { it.fontMetrics }
        val ascent = metrics.minOf { it.ascent }
        val descent = metrics.maxOf { it.descent }
        val leading = metrics.maxOf { it.leading }
        cellHeight = (descent - ascent + leading).coerceAtLeast(1f)
        baseline = -ascent
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

    /**
     * Repaint every row, not just the cells the last write touched. A fresh
     * stream has no local dirty region to rely on: its replay may start with
     * the screen still showing the previous connection. onDraw always paints
     * the whole page, so one invalidate is the full redraw.
     */
    fun redrawAll() {
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

        if (selecting) drawSelection(canvas, buffer)

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

        if (glyphRun.size < count) glyphRun = CharArray(count)
        val y = top + baseline
        var runStart = 0
        var runLen = 0
        var runPaint: Paint? = null
        var runColor = 0

        fun flushRun() {
            if (runLen == 0) return
            canvas.drawText(glyphRun, 0, runLen, runStart * cellWidth, y, runPaint!!)
            runLen = 0
        }

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
                val color = applyDim(cellFg, attr)
                val x = col * cellWidth
                val combining = row.combiningAt(col)
                // Plain ASCII cells can be drawn a row-run at a time. Wide,
                // combining, and fallback-font glyphs stay one by one so their
                // advances cannot drift away from the cell grid.
                if (combining == null && cp < 0x80 && row.width[col].toInt() == 1) {
                    // Flush before changing a shared Paint's colour: the run
                    // that is already open still owns the old colour.
                    if (runLen > 0 && (p !== runPaint || color != runColor)) flushRun()
                    if (runLen == 0) {
                        runPaint = p
                        runColor = color
                        p.color = color
                        runStart = col
                    }
                    glyphRun[runLen++] = cp.toChar()
                } else {
                    flushRun()
                    p.color = color
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
                }
                if (attr and TerminalAttr.UNDERLINE != 0) drawUnderline(canvas, x, top, row.width[col], color)
                if (attr and TerminalAttr.STRIKE != 0) drawStrike(canvas, x, top, row.width[col], color)
            } else {
                flushRun()
            }
            col += maxOf(1, row.width[col].toInt())
        }
        flushRun()
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
    // selection and copy
    // =======================================================================

    /** The ordered selection: start row/col then end row/col, both inclusive. */
    private fun orderedSelection(): IntArray {
        val forward = selAnchorRow < selCursorRow ||
            (selAnchorRow == selCursorRow && selAnchorCol <= selCursorCol)
        return if (forward) {
            intArrayOf(selAnchorRow, selAnchorCol, selCursorRow, selCursorCol)
        } else {
            intArrayOf(selCursorRow, selCursorCol, selAnchorRow, selAnchorCol)
        }
    }

    private fun drawSelection(canvas: Canvas, buffer: TerminalBuffer) {
        val count = buffer.lines.size
        if (count == 0) return
        val s = orderedSelection()
        val first = s[0].coerceIn(0, count - 1)
        val last = s[2].coerceIn(0, count - 1)
        for (r in first..last) {
            val row = buffer.lines[r]
            if (row.columns == 0) continue
            val from: Int
            val to: Int
            if (first == last) {
                from = s[1].coerceIn(0, row.columns - 1)
                to = s[3].coerceIn(0, row.columns - 1)
            } else if (r == first) {
                from = s[1].coerceIn(0, row.columns - 1)
                to = row.columns - 1
            } else if (r == last) {
                from = 0
                to = s[3].coerceIn(0, row.columns - 1)
            } else {
                from = 0
                to = row.columns - 1
            }
            if (to < from) continue
            canvas.drawRect(
                from * cellWidth,
                r * cellHeight,
                (to + 1) * cellWidth,
                (r + 1) * cellHeight,
                selectionPaint,
            )
        }
    }

    /** Long press: select the word under the finger, or the cell if it is blank. */
    private fun beginSelection(x: Float, y: Float) {
        val em = emulator ?: return
        val buffer = em.buffer
        if (buffer.lines.isEmpty() || cellWidth <= 0f || cellHeight <= 0f) return
        val row = (y / cellHeight).toInt().coerceIn(0, buffer.lines.size - 1)
        val line = buffer.lines[row]
        if (line.columns == 0) return
        val col = (x / cellWidth).toInt().coerceIn(0, line.columns - 1)

        var from = col
        var to = col
        if (line.hasGlyph(col)) {
            while (from > 0 && isWordCell(line, from - 1)) from--
            while (to < line.columns - 1 && isWordCell(line, to + 1)) to++
        }
        selAnchorRow = row
        selAnchorCol = from
        selCursorRow = row
        selCursorCol = to
        setSelecting(true)
        selectingDrag = false
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        startSelectionMode()
        invalidate()
    }

    private fun setSelecting(value: Boolean) {
        if (selecting == value) return
        selecting = value
        onSelectionChanged?.invoke(value)
    }

    private fun isWordCell(row: TerminalRow, col: Int): Boolean {
        val cp = row.chars[col]
        if (cp == 0 || cp == TerminalRow.SPACE) return false
        return !Character.isWhitespace(cp)
    }

    /** Drag: the cursor follows the finger; the anchor stays where the press began. */
    private fun extendSelection(x: Float, y: Float) {
        val em = emulator ?: return
        val buffer = em.buffer
        if (buffer.lines.isEmpty() || cellWidth <= 0f || cellHeight <= 0f) return
        val row = (y / cellHeight).toInt().coerceIn(0, buffer.lines.size - 1)
        val line = buffer.lines[row]
        if (line.columns == 0) return
        val col = (x / cellWidth).toInt().coerceIn(0, line.columns - 1)
        if (row == selCursorRow && col == selCursorCol) return
        val rowChanged = row != selCursorRow
        selCursorRow = row
        selCursorCol = col
        if (rowChanged) selectionMode?.invalidate()
        invalidate()
    }

    private fun selectAll() {
        val em = emulator ?: return
        val buffer = em.buffer
        if (buffer.lines.isEmpty()) return
        setSelecting(true)
        selectingDrag = false
        selAnchorRow = 0
        selAnchorCol = 0
        selCursorRow = buffer.lines.size - 1
        selCursorCol = buffer.lines.last().columns - 1
        invalidate()
        startSelectionMode()
        selectionMode?.invalidate()
    }

    private fun clearSelection() {
        if (!selecting) return
        setSelecting(false)
        invalidate()
        selectionMode?.finish()
        selectionMode = null
    }

    /** Everything between the anchor and the cursor, as the terminal would copy it. */
    private fun selectedText(): String {
        val em = emulator ?: return ""
        val buffer = em.buffer
        if (buffer.lines.isEmpty()) return ""
        val s = orderedSelection()
        val firstRow = s[0].coerceIn(0, buffer.lines.size - 1)
        val lastRow = s[2].coerceIn(0, buffer.lines.size - 1)
        val sb = StringBuilder()
        for (r in firstRow..lastRow) {
            val row = buffer.lines[r]
            if (row.columns == 0) continue
            val from = (if (r == firstRow) s[1] else 0).coerceIn(0, row.columns - 1)
            val to = (if (r == lastRow) s[3] else row.columns - 1).coerceIn(0, row.columns - 1)
            val line = StringBuilder()
            var col = from
            while (col <= to) {
                val cp = row.chars[col]
                when {
                    cp == 0 -> Unit // a wide glyph's continuation cell
                    cp == TerminalRow.SPACE -> line.append(' ')
                    else -> {
                        line.appendCodePoint(cp)
                        row.combiningAt(col)?.let { line.append(it) }
                    }
                }
                col += maxOf(1, row.width[col].toInt())
            }
            sb.append(line.toString().trimEnd(' '))
            // A wrapped row is one logical line: join it without a break.
            if (r < lastRow && !row.wrapped) sb.append('\n')
        }
        return sb.toString()
    }

    private fun copySelection() {
        val text = selectedText()
        if (text.isNotEmpty()) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("terminal", text))
        }
        clearSelection()
    }

    private fun startSelectionMode() {
        if (selectionMode != null) return
        selectionMode = startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(Menu.NONE, MENU_COPY, Menu.NONE, context.getString(R.string.terminal_copy))
                menu.add(
                    Menu.NONE,
                    MENU_SELECT_ALL,
                    Menu.NONE,
                    context.getString(R.string.terminal_select_all),
                )
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = when (item.itemId) {
                MENU_COPY -> {
                    copySelection()
                    true
                }

                MENU_SELECT_ALL -> {
                    selectAll()
                    true
                }

                else -> false
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                if (!selecting) return
                setSelecting(false)
                selectionMode = null
                invalidate()
            }

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                if (!selecting) {
                    outRect.set(0, 0, view.width, view.height)
                    return
                }
                val s = orderedSelection()
                val first = s[0]
                val last = s[2]
                val left = if (first == last) minOf(s[1], s[3]) * cellWidth else 0f
                val right = if (first == last) {
                    (maxOf(s[1], s[3]) + 1) * cellWidth
                } else {
                    view.width.toFloat()
                }
                outRect.set(
                    left.toInt(),
                    (first * cellHeight).toInt(),
                    right.toInt(),
                    ((last + 1) * cellHeight).toInt(),
                )
            }
        }, ActionMode.TYPE_FLOATING)
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
                removeCallbacks(longPress)
                if (selecting) clearSelection()
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
                // A press that holds still becomes a selection instead of a
                // mouse click; releasing or moving cancels the timer.
                postDelayed(longPress, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger is always a scroll.
                removeCallbacks(longPress)
                lastTouchY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (selecting) {
                    if (!selectingDrag &&
                        (kotlin.math.abs(event.y - downY) > touchSlop ||
                            kotlin.math.abs(event.x - downX) > touchSlop)
                    ) {
                        selectingDrag = true
                    }
                    if (selectingDrag) extendSelection(event.x, event.y)
                    return true
                }
                velocityTracker.addMovement(event)
                val dx = event.x - downX
                val dy = lastTouchY - event.y
                // The drawer watches horizontal drags above this view; a
                // vertical drag belongs to the terminal. Once the finger has
                // moved past the slop, it is no longer a long press.
                if (!dragging &&
                    (kotlin.math.abs(event.y - downY) > touchSlop ||
                        kotlin.math.abs(dx) > touchSlop)
                ) {
                    dragging = true
                    removeCallbacks(longPress)
                }
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
                removeCallbacks(longPress)
                velocityTracker.addMovement(event)
                if (selecting) {
                    // The finger has left the screen; the selection stays put
                    // until Copy, Select all, or a tap elsewhere. A long press
                    // that never moved keeps the whole word it selected.
                    if (selectingDrag) extendSelection(event.x, event.y)
                    return true
                }
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
                }
                if (quick) {
                    // Full-screen programs (opencode) are exactly where typing
                    // is needed most. Showing the IME here used to be limited
                    // to the non-mouse branch, so a tap closed the keyboard.
                    keepSoftKeyboard()
                    onUserInteraction?.invoke()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
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
        // A terminal must not be treated as a password field. The visible-
        // password variation used to suppress suggestions, but some OEM
        // keyboards/screenshot services (vivo/iQOO among them) then refuse a
        // screenshot of the whole screen while the keyboard is up. A normal
        // text field with suggestions disabled keeps the IME quiet without
        // claiming the content is a secret.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
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

    /**
     * Keep the keyboard up after a tap on the chrome (Ctrl, Alt, a key chip).
     * Taking focus back from the Compose text field is what the system uses as
     * its cue to hide the IME, so ask again once that focus change settles.
     */
    fun keepSoftKeyboard() {
        if (!isFocused) requestFocus()
        removeCallbacks(restoreKeyboard)
        post(restoreKeyboard)
        postDelayed(restoreKeyboard, KEYBOARD_RESTORE_MS)
    }

    fun hideSoftKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(pendingResize)
        removeCallbacks(longPress)
        removeCallbacks(restoreKeyboard)
        setSelecting(false)
        selectionMode = null
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
