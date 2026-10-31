package com.tulipskun.aixodia.term

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
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
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

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

private const val HANDLE_NONE = 0
private const val HANDLE_START = 1
private const val HANDLE_END = 2

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

    /**
     * Called when the current drag is locked to the vertical axis. The drawer
     * must stop watching horizontal movement for the rest of that gesture:
     * otherwise a little sideways drift halfway through a scroll hands the
     * touch stream to the menu and the terminal stops moving under the finger.
     */
    var onVerticalDrag: ((vertical: Boolean) -> Unit)? = null

    /** Called after a latched modifier is spent, so the key row can un-light. */
    var onModsConsumed: (() -> Unit)? = null

    // Latched by the Ctrl/Alt/Shift chips: they apply to the next thing
    // typed, then clear themselves, exactly as a one-shot modifier should.
    // Shift matters to one key only — Tab — because the soft keyboard
    // supplies letter case itself; a latched Shift plus Tab is the backtab.
    private var latchedCtrl = false
    private var latchedAlt = false
    private var latchedShift = false

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
    // What the font asks for one line, and what a row is actually painted at.
    // The row is stretched to divide the view exactly; see resizeToView.
    private var lineHeight = 1f
    private var cellHeight = 1f
    private var baseline = 0f

    // How far the page is pulled down over the live screen, in px: 0 shows the
    // prompt, more shows the scrollback the normal buffer keeps above it.
    private var scrollTopPx = 0f
    private var pinnedToBottom = true
    private var lastHistoryRows = 0
    private val scroller = OverScroller(context)
    private val velocityTracker = VelocityTracker.obtain()
    private var lastTouchY = 0f
    private var downY = 0f
    private var downX = 0f
    private var downTime = 0L
    private var dragging = false
    private var wheelAccum = 0f
    // A drag chooses one axis at the first touch slop and keeps it until the
    // finger lifts. A vertical scroll must never become a drawer swipe midway.
    private var dragAxisLocked = false
    private var verticalDrag = false
    // A press that landed on a live selection is dismissing it, not asking for
    // the keyboard: reading and copying text must not summon the IME.
    private var pressDismissedSelection = false

    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity = android.view.ViewConfiguration.get(context).scaledMinimumFlingVelocity

    // Text selection: a long press selects a word and raises a floating action
    // bar with Copy. Both ends stay where the word starts and ends; the two
    // round handles below move one end each, the way text selection works in a
    // browser. The endpoints are always ordered, so copying is one walk from
    // the earlier cell to the later one.
    private val selectionPaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0x664A90D9
    }
    private val density = resources.displayMetrics.density
    private val handleRadius = 8f * density
    private val handleGap = 2f * density
    private val handleTouchRadius = 24f * density
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0xFF4A90D9.toInt()
    }
    private var selecting = false
    private var selectingDrag = false
    private var selectingHandle = HANDLE_NONE
    private var selStartRow = 0
    private var selStartCol = 0
    private var selEndRow = 0
    private var selEndCol = 0
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
        lineHeight = (descent - ascent + leading).coerceAtLeast(1f)
        cellHeight = lineHeight
        baseline = -ascent
    }

    fun setEmulator(emulator: TerminalEmulator) {
        if (this.emulator !== emulator) clearSelection()
        this.emulator = emulator
        resetScroll()
        resizeToView()
        invalidate()
    }

    fun emulator(): TerminalEmulator? = emulator

    // =======================================================================
    // geometry
    // =======================================================================

    /**
     * The keyboard slides in over several frames and the view is re-measured on
     * each one. Telling the shell to resize on every frame makes a full-screen
     * program repaint its whole screen each time, and moving the grid on every
     * frame is worse than either: it hands rows back and forth between the live
     * screen and the history while the shell is still writing to the size it was
     * last told, so output lands against a bottom edge that is moving. The floor
     * is therefore one settled size — grid and report together, once the layout
     * has been still for [RESIZE_SETTLE_MS].
     */
    private val pendingResize = Runnable { resizeToView() }
    private var sizedAtLeastOnce = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        removeCallbacks(pendingResize)
        if (sizedAtLeastOnce) {
            // The keyboard is still moving. Let the layout settle first; the old
            // grid keeps drawing (clipped, if it is taller) until then.
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

    /**
     * Fit the grid to the view, holding the reader's place in the page, and tell
     * the shell the size it has never been given.
     *
     * Which row of the page sits at the top of the view is the anchor: rows of the
     * whole document, the history above the live screen and then its rows. A
     * resize moves rows across that boundary in both directions — down into the
     * history when the screen shortens, back up onto it when it lengthens — and
     * the screen-change callback `em.resize` fires only compensates for the first
     * of those, so the page is re-derived from the anchor rather than left to a
     * one-way delta.
     */
    private fun resizeToView() {
        val em = emulator ?: return
        if (cellWidth <= 0f || lineHeight <= 0f || width == 0 || height == 0) return
        val cols = (width / cellWidth).toInt().coerceAtLeast(1)
        // The row count is whole cells that fit; the strip left over below the
        // last row used to stay empty, which read as a blank line above the
        // keys. Share it out over the rows instead, so the page ends exactly
        // where the view does.
        val rows = (height / lineHeight).toInt().coerceAtLeast(1)
        val oldCellHeight = cellHeight
        cellHeight = height / rows.toFloat()
        val historyBefore = em.buffer.scrollback.size
        val topRowBefore = if (scrollTopPx > 0f) historyBefore - scrollTopPx / oldCellHeight else null
        if (cols == em.columns && rows == em.rows) {
            if (topRowBefore != null) {
                scrollTopPx = (em.buffer.scrollback.size - topRowBefore) * cellHeight
                clampScroll()
            }
            if (cellHeight != oldCellHeight) invalidate()
            return
        }
        em.resize(cols, rows)
        onResize?.invoke(cols, rows)
        if (topRowBefore != null) {
            scrollTopPx = ((em.buffer.scrollback.size - topRowBefore) * cellHeight).coerceAtLeast(0f)
        }
        lastHistoryRows = em.buffer.scrollback.size
        clampScroll()
        invalidate()
    }


    /**
     * Rows of history the page may be pulled down over the live screen.
     * The alternate screen has none of its own, and a program that asked for
     * the mouse takes a drag as a wheel instead, so in both cases the page
     * stays exactly where the program put it.
     */
    private fun maxScrollPx(): Float {
        val em = emulator ?: return 0f
        if (em.mouseReporting() || em.alternateScreen) return 0f
        return em.buffer.scrollback.size * cellHeight
    }

    private fun clampScroll() {
        scrollTopPx = scrollTopPx.coerceIn(0f, maxScrollPx())
        val atBottom = scrollTopPx <= 0f
        if (atBottom != pinnedToBottom) {
            pinnedToBottom = atBottom
            onScrollChanged?.invoke(atBottom)
        }
    }

    /** Set the one-shot Ctrl/Alt/Shift chips; they are spent by the next keystroke. */
    fun setLatchedMods(ctrl: Boolean, alt: Boolean, shift: Boolean) {
        latchedCtrl = ctrl
        latchedAlt = alt
        latchedShift = shift
    }

    /**
     * Text produced by the keyboard. A latched modifier is applied to the
     * first character and then released, so Ctrl then c really does send 0x03;
     * Shift leaves a letter the IME already capitalised alone and capitalises
     * a lowercase one. The rest of a multi-character commit rides unchanged.
     */
    fun sendTyped(text: String) {
        if (text.isEmpty()) return
        if (!latchedCtrl && !latchedAlt && !latchedShift) {
            sendText(text)
            return
        }
        val mods = latchMods()
        consumeLatch()
        sendText(TerminalKeys.typed(text, mods))
    }

    private fun latchMods() = KeyMods(latchedCtrl, latchedAlt, latchedShift)

    /** Sends a key the on-screen bar drew, then spends the latches like a keystroke. */
    fun sendChord(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        sendBytes(bytes)
        consumeLatch()
    }

    /** An arrow from the bar: application mode and the latches belong to the view. */
    fun sendBarCursor(final: Char) {
        val mods = latchMods()
        val bytes = emulator?.cursorKey(final, mods)
            ?: TerminalKeys.cursor(final, mods, false).toByteArray(Charsets.US_ASCII)
        sendBytes(bytes)
        consumeLatch()
    }

    /**
     * The IME's Backspace. A latched modifier makes it that chord — Ctrl is the
     * readline word-erase — the same bytes the hardware path sends.
     */
    fun deleteBackward(count: Int) {
        if (count <= 0) return
        val mods = latchMods()
        if (mods.any) {
            sendText(TerminalKeys.backspace(mods))
            consumeLatch()
            return
        }
        repeat(count) { sendText(TerminalKeys.backspace(KeyMods.NONE)) }
    }

    /** Called by the panel whenever the emulator produced output. */
    fun onContentChanged() {
        // New output pushes rows off the live screen into the history above it.
        // Measuring the page from the bottom, each of those rows would slide the
        // text the reader is looking at down one line; adding the same height
        // back holds the history still, the way a terminal does when you read it
        // while something keeps printing.
        val rows = emulator?.buffer?.scrollback?.size ?: 0
        if (rows > lastHistoryRows && scrollTopPx > 0f) {
            scrollTopPx += (rows - lastHistoryRows) * cellHeight
        }
        lastHistoryRows = rows
        clampScroll()
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
        resetScroll()
        invalidate()
    }

    /** Back onto the live screen, with the history count re-anchored to it. */
    private fun resetScroll() {
        scroller.forceFinished(true)
        scrollTopPx = 0f
        lastHistoryRows = emulator?.buffer?.scrollback?.size ?: 0
        clampScroll()
    }

    fun pageUp() {
        // PgUp stays a key rather than a page of local history: a program that
        // owns the screen (less, vim, opencode) pages with exactly this byte,
        // and reading a plain prompt's scrollback is what dragging is for.
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
        val pan = scrollTopPx
        // The page is the history the normal buffer keeps, with the live screen
        // under it. At rest the screen starts at the top of the view; pulling the
        // page down slides the newest history rows in above it.
        if (pan > 0f) {
            val history = buffer.scrollback
            var i = history.size - 1
            while (i >= 0) {
                val top = (i - history.size) * cellHeight + pan
                if (top + cellHeight <= 0f) break
                if (top < height.toFloat()) drawRow(canvas, history[i], top, i)
                i--
            }
        }
        for (v in 0 until buffer.lines.size) {
            val top = v * cellHeight + pan
            if (top >= height) break
            drawRow(canvas, buffer.lines[v], top, v)
        }

        if (selecting) drawSelection(canvas, buffer)

        if (em.cursorVisible && hasFocus()) {
            drawCursor(canvas, em, buffer, buffer.cursorRow * cellHeight + pan)
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

    private fun comparePositions(r1: Int, c1: Int, r2: Int, c2: Int): Int = when {
        r1 < r2 -> -1
        r1 > r2 -> 1
        c1 < c2 -> -1
        c1 > c2 -> 1
        else -> 0
    }

    /**
     * Paint only the cells that actually contain text. A multi-row selection
     * used to fill the whole terminal width from edge to edge, which made a
     * copied line look like it included the empty right half of the screen.
     */
    private fun drawSelection(canvas: Canvas, buffer: TerminalBuffer) {
        val count = buffer.lines.size
        if (count == 0) return
        val first = selStartRow.coerceIn(0, count - 1)
        val last = selEndRow.coerceIn(0, count - 1)
        for (r in first..last) {
            val row = buffer.lines[r]
            if (row.columns == 0) continue
            val rawFrom = if (r == first) selStartCol else 0
            val rawTo = if (r == last) selEndCol else row.columns - 1
            val from = rawFrom.coerceIn(0, row.columns - 1)
            val to = rawTo.coerceIn(0, row.columns - 1)
            val textEnd = TerminalSelection.lastTextCol(row)
            if (textEnd < from || to < from) continue
            canvas.drawRect(
                from * cellWidth,
                r * cellHeight + scrollTopPx,
                (minOf(to, textEnd) + 1) * cellWidth,
                (r + 1) * cellHeight + scrollTopPx,
                selectionPaint,
            )
        }
        drawSelectionHandles(canvas, buffer)
    }

    /** Start and end handle centres, followed by the two selection boundaries. */
    private fun handleGeometry(buffer: TerminalBuffer): FloatArray {
        val count = buffer.lines.size
        val first = selStartRow.coerceIn(0, count - 1)
        val last = selEndRow.coerceIn(0, count - 1)
        val minX = handleRadius
        val maxX = (width - handleRadius).coerceAtLeast(minX)
        val startX = (selStartCol * cellWidth - handleRadius * 0.45f).coerceIn(minX, maxX)
        val endX = ((selEndCol + 1) * cellWidth + handleRadius * 0.45f).coerceIn(minX, maxX)
        val startY = first * cellHeight + scrollTopPx
        val endY = (last + 1) * cellHeight + scrollTopPx
        val startCentreY = if (startY - handleRadius - handleGap >= 0f) {
            startY - handleRadius - handleGap
        } else {
            startY + handleRadius + handleGap
        }
        val endCentreY = if (endY + handleRadius + handleGap <= height) {
            endY + handleRadius + handleGap
        } else {
            endY - handleRadius - handleGap
        }
        return floatArrayOf(startX, startCentreY, endX, endCentreY, startY, endY)
    }

    private fun drawSelectionHandles(canvas: Canvas, buffer: TerminalBuffer) {
        if (buffer.lines.isEmpty()) return
        val g = handleGeometry(buffer)
        // A short stem joins each round grip to the exact start or end cell.
        canvas.drawLine(g[0], g[4], g[0], g[1], handleStrokePaint)
        canvas.drawLine(g[2], g[5], g[2], g[3], handleStrokePaint)
        canvas.drawCircle(g[0], g[1], handleRadius, handleFillPaint)
        canvas.drawCircle(g[0], g[1], handleRadius, handleStrokePaint)
        canvas.drawCircle(g[2], g[3], handleRadius, handleFillPaint)
        canvas.drawCircle(g[2], g[3], handleRadius, handleStrokePaint)
    }

    /** Which selection handle, if any, is under the finger. */
    private fun selectionHandleAt(x: Float, y: Float): Int {
        val em = emulator ?: return HANDLE_NONE
        if (em.buffer.lines.isEmpty()) return HANDLE_NONE
        val g = handleGeometry(em.buffer)
        val dsx = x - g[0]
        val dsy = y - g[1]
        val dex = x - g[2]
        val dey = y - g[3]
        val startDistance = dsx * dsx + dsy * dsy
        val endDistance = dex * dex + dey * dey
        val reach = handleTouchRadius * handleTouchRadius
        // A one-cell selection puts the grips close together. Pick the nearer
        // one instead of always preferring the start handle.
        return when {
            startDistance <= reach && endDistance <= reach ->
                if (startDistance <= endDistance) HANDLE_START else HANDLE_END
            startDistance <= reach -> HANDLE_START
            endDistance <= reach -> HANDLE_END
            else -> HANDLE_NONE
        }
    }

    /**
     * Long press: select the word under the finger. A press on the blank part
     * of a line snaps to the nearest word on that line instead of selecting a
     * lone blank cell; a blank line has nothing to select.
     */
    private fun beginSelection(x: Float, y: Float) {
        val em = emulator ?: return
        val buffer = em.buffer
        if (buffer.lines.isEmpty() || cellWidth <= 0f || cellHeight <= 0f) return
        val row = ((y - scrollTopPx) / cellHeight).toInt().coerceIn(0, buffer.lines.size - 1)
        val line = buffer.lines[row]
        if (line.columns == 0) return
        var col = (x / cellWidth).toInt().coerceIn(0, line.columns - 1)
        if (!line.hasGlyph(col)) {
            col = nearestGlyphCol(line, col)
            if (col < 0) return
        }

        var from = col
        var to = col
        while (from > 0 && isWordCell(line, from - 1)) from--
        while (to < line.columns - 1 && isWordCell(line, to + 1)) to++
        selStartRow = row
        selStartCol = from
        selEndRow = row
        selEndCol = to
        selectingHandle = HANDLE_NONE
        setSelecting(true)
        selectingDrag = false
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        startSelectionMode()
        invalidate()
    }

    private fun nearestGlyphCol(row: TerminalRow, col: Int): Int {
        var left = col
        while (left >= 0 && !row.hasGlyph(left)) left--
        var right = col
        while (right < row.columns && !row.hasGlyph(right)) right++
        return when {
            left >= 0 && (right >= row.columns || col - left <= right - col) -> left
            right < row.columns -> right
            else -> -1
        }
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

    /**
     * Dragging after a long press only grows the word it started on. Moving
     * back into the word must not shrink it: the handles exist for fine
     * adjustments in both directions.
     */
    private fun extendSelection(x: Float, y: Float) {
        val em = emulator ?: return
        val buffer = em.buffer
        if (buffer.lines.isEmpty() || cellWidth <= 0f || cellHeight <= 0f) return
        val row = ((y - scrollTopPx) / cellHeight).toInt().coerceIn(0, buffer.lines.size - 1)
        val line = buffer.lines[row]
        if (line.columns == 0) return
        val col = (x / cellWidth).toInt().coerceIn(0, line.columns - 1)
        var changed = false
        if (comparePositions(row, col, selStartRow, selStartCol) < 0) {
            selStartRow = row
            selStartCol = col
            changed = true
        }
        if (comparePositions(row, col, selEndRow, selEndCol) > 0) {
            selEndRow = row
            selEndCol = col
            changed = true
        }
        if (changed) {
            invalidate()
            selectionMode?.invalidateContentRect()
        }
    }

    /** Move one end of the selection; crossing the other end swaps the grips. */
    private fun moveSelectionHandle(x: Float, y: Float) {
        val em = emulator ?: return
        val buffer = em.buffer
        if (buffer.lines.isEmpty() || cellWidth <= 0f || cellHeight <= 0f) return
        val row = ((y - scrollTopPx) / cellHeight).toInt().coerceIn(0, buffer.lines.size - 1)
        val line = buffer.lines[row]
        if (line.columns == 0) return
        val col = (x / cellWidth).toInt().coerceIn(0, line.columns - 1)
        when (selectingHandle) {
            HANDLE_START -> {
                if (comparePositions(row, col, selEndRow, selEndCol) > 0) {
                    selStartRow = selEndRow
                    selStartCol = selEndCol
                    selEndRow = row
                    selEndCol = col
                    selectingHandle = HANDLE_END
                } else {
                    selStartRow = row
                    selStartCol = col
                }
            }
            HANDLE_END -> {
                if (comparePositions(row, col, selStartRow, selStartCol) < 0) {
                    selEndRow = selStartRow
                    selEndCol = selStartCol
                    selStartRow = row
                    selStartCol = col
                    selectingHandle = HANDLE_START
                } else {
                    selEndRow = row
                    selEndCol = col
                }
            }
            else -> return
        }
        invalidate()
        selectionMode?.invalidateContentRect()
    }

    private fun selectAll() {
        val em = emulator ?: return
        val buffer = em.buffer
        val first = firstTextPosition(buffer) ?: return
        val last = lastTextPosition(buffer) ?: return
        setSelecting(true)
        selectingDrag = false
        selectingHandle = HANDLE_NONE
        selStartRow = first[0]
        selStartCol = first[1]
        selEndRow = last[0]
        selEndCol = last[1]
        invalidate()
        startSelectionMode()
        selectionMode?.invalidateContentRect()
    }

    private fun firstTextPosition(buffer: TerminalBuffer): IntArray? {
        for (r in buffer.lines.indices) {
            val col = TerminalSelection.firstTextCol(buffer.lines[r])
            if (col >= 0) return intArrayOf(r, col)
        }
        return null
    }

    private fun lastTextPosition(buffer: TerminalBuffer): IntArray? {
        for (r in buffer.lines.indices.reversed()) {
            val col = TerminalSelection.lastTextCol(buffer.lines[r])
            if (col >= 0) return intArrayOf(r, col)
        }
        return null
    }

    private fun clearSelection() {
        if (!selecting) return
        setSelecting(false)
        selectingHandle = HANDLE_NONE
        selectingDrag = false
        invalidate()
        selectionMode?.finish()
        selectionMode = null
    }

    private fun selectedText(): String {
        val em = emulator ?: return ""
        return TerminalSelection.text(
            em.buffer,
            selStartRow,
            selStartCol,
            selEndRow,
            selEndCol,
        )
    }

    private fun copySelection() {
        val text = selectedText()
        if (text.isNotEmpty()) {
            val cm = context.getSystemService(ClipboardManager::class.java)
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
                // Android 13 and later show the system clipboard preview by
                // themselves. A second Toast covers that preview and makes the
                // copy look like it never reached the clipboard, so keep our
                // own confirmation only on releases that have no system UI.
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                }
            }
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
                selectionMode = null
                selectingHandle = HANDLE_NONE
                selectingDrag = false
                if (!selecting) return
                setSelecting(false)
                invalidate()
            }

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                if (!selecting) {
                    outRect.set(0, 0, view.width, view.height)
                    return
                }
                val first = selStartRow
                val last = selEndRow
                val left = if (first == last) minOf(selStartCol, selEndCol) * cellWidth else 0f
                val right = if (first == last) {
                    (maxOf(selStartCol, selEndCol) + 1) * cellWidth
                } else {
                    view.width.toFloat()
                }
                outRect.set(
                    left.toInt(),
                    (first * cellHeight + scrollTopPx).toInt(),
                    right.toInt(),
                    ((last + 1) * cellHeight + scrollTopPx).toInt(),
                )
            }
        }, ActionMode.TYPE_FLOATING)
    }

    // =======================================================================
    // touch
    // =======================================================================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val em = emulator ?: return false
        // A drag moves the page unless the program has taken the viewport for
        // itself: an explicit mouse mode, or the alternate screen, which has no
        // history of ours to show anyway. The full-screen *guess* deliberately
        // does not count — it is set by any program that parks the cursor a few
        // times and is never cleared while an inline TUI runs, so trusting it
        // here left a plain prompt unable to scroll for the rest of the session.
        val panMode = !em.mouseReporting() && !em.alternateScreen
        val wheelMode = !panMode
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                removeCallbacks(longPress)
                selectingHandle = HANDLE_NONE
                pressDismissedSelection = selecting
                if (selecting) {
                    val handle = selectionHandleAt(event.x, event.y)
                    if (handle != HANDLE_NONE) {
                        selectingHandle = handle
                        selectingDrag = false
                        return true
                    }
                    clearSelection()
                }
                // A tap is a tap and nothing more: it must not take focus, or
                // the command field loses the IME and the keyboard falls shut
                // under the finger that only meant to click or look.
                scroller.forceFinished(true)
                lastTouchY = event.y
                downY = event.y
                downX = event.x
                downTime = event.eventTime
                dragging = false
                endDragAxis()
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
                dragAxisLocked = true
                if (!verticalDrag) {
                    verticalDrag = true
                    onVerticalDrag?.invoke(true)
                }
                lastTouchY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (selecting) {
                    if (selectingHandle != HANDLE_NONE) {
                        moveSelectionHandle(event.x, event.y)
                        return true
                    }
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
                val totalDy = event.y - downY
                val dy = lastTouchY - event.y
                // Decide the axis once. Until the finger lifts, a vertical
                // scroll stays a scroll even if it drifts sideways later; that
                // drift used to hand the gesture to the drawer mid-scroll.
                // The page only claims a drag that is clearly vertical — the
                // drawer is a horizontal gesture and answers the rest.
                if (!dragAxisLocked) {
                    when (dragAxis(dx, totalDy, touchSlop.toFloat())) {
                        DragAxis.Vertical -> {
                            dragAxisLocked = true
                            verticalDrag = true
                            onVerticalDrag?.invoke(true)
                        }
                        DragAxis.Horizontal -> {
                            dragAxisLocked = true
                            verticalDrag = false
                        }
                        null -> Unit
                    }
                }
                if (!dragging && dragAxisLocked) {
                    dragging = true
                    removeCallbacks(longPress)
                }
                lastTouchY = event.y
                // A locked vertical drag belongs to the terminal: an ancestor
                // that also watches drags (the drawer) must not take over.
                if (verticalDrag) parent?.requestDisallowInterceptTouchEvent(true)
                if (wheelMode && verticalDrag) {
                    wheelAccum += dy
                    dispatchWheel(em, event)
                } else if (verticalDrag) {
                    // Nobody else is holding the screen, so the page follows the
                    // finger: down pulls the history into view, up puts it back
                    // and walks towards the prompt.
                    scroller.forceFinished(true)
                    scrollTopPx -= dy
                    clampScroll()
                    invalidate()
                }
                // A horizontal drag belongs to the drawer.
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                velocityTracker.addMovement(event)
                if (verticalDrag && !wheelMode) releaseFling()
                endDragAxis()
                if (selecting) {
                    // The finger has left the screen; the selection stays put
                    // until Copy, Select all, a handle drag, or a tap elsewhere.
                    if (selectingHandle != HANDLE_NONE) {
                        selectingHandle = HANDLE_NONE
                        return true
                    }
                    if (selectingDrag) extendSelection(event.x, event.y)
                    selectingDrag = false
                    return true
                }
                val quick = event.eventTime - downTime < 200
                // A tap is a click. A drag has already gone out as wheel
                // notches, so it must not also fire a click. On the alternate
                // screen with no mouse mode the program deliberately did not ask
                // for clicks; only the mid-stream case is a guess.
                val guessedMouse = em.fullScreenDetected && !em.alternateScreen
                if (!dragging && quick && (em.mouseReporting() || guessedMouse)) {
                    sendMouse(em, event, button = 0, release = false)
                    sendMouse(em, event, button = 0, release = true)
                }
                // A tap is a tap and nothing more. It used to raise the IME,
                // which put the keyboard over the grid every time a reader
                // touched the page to click, scroll back, or simply look. The
                // command field is where the keyboard belongs, and the chrome
                // keeps its own keyboard up around it.
                if (quick && !dragging && !pressDismissedSelection) {
                    onUserInteraction?.invoke()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (selecting) {
                    selectingHandle = HANDLE_NONE
                    selectingDrag = false
                    return true
                }
                dragging = false
                endDragAxis()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** Releases the gesture's axis lock and re-arms the drawer for the next one. */
    private fun endDragAxis() {
        if (verticalDrag) onVerticalDrag?.invoke(false)
        dragAxisLocked = false
        verticalDrag = false
    }

    /** Carries a flick of the page into a decelerating scroll. */
    private fun releaseFling() {
        val max = maxScrollPx()
        if (max <= 0f) return
        velocityTracker.computeCurrentVelocity(1000)
        val vy = velocityTracker.yVelocity
        if (kotlin.math.abs(vy) < minFlingVelocity) return
        // The page runs with the finger: a flick downwards is positive y and
        // keeps pulling history in, exactly as the drag it continues did.
        scroller.fling(0, scrollTopPx.toInt(), 0, vy.toInt(), 0, 0, 0, max.toInt())
        postInvalidateOnAnimation()
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
        // Keep the terminal an ordinary text editor. The visible-password
        // variation made some OEM keyboards/screenshot services (vivo/iQOO
        // among them) refuse a screenshot while the keyboard was up, and
        // private/incognito keyboard modes disable Gboard's clipboard history.
        // Normal suggestions are fine here: the terminal still sends each
        // committed character through its input connection.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        return TerminalInputConnection(this, false)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handleKey(event)) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun sendText(text: String) {
        if (text.isEmpty()) return
        // Text lands at the prompt, so the page goes back to look at it rather
        // than typing blind into history.
        if (scrollTopPx > 0f) scrollToBottom()
        inputSink?.invoke(text.toByteArray(Charsets.UTF_8))
    }

    /** Maps a key press to the bytes a terminal expects. */
    fun handleKey(event: KeyEvent): Boolean {
        val em = emulator
        val mods = KeyMods(
            ctrl = event.isCtrlPressed || latchedCtrl,
            alt = event.isAltPressed || latchedAlt,
            shift = event.isShiftPressed || latchedShift,
        )
        var handled = true
        when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                sendText(TerminalKeys.enter(mods))
            KeyEvent.KEYCODE_DEL -> sendText(TerminalKeys.backspace(mods))
            KeyEvent.KEYCODE_FORWARD_DEL -> sendText(TerminalKeys.tilde(3, mods))
            KeyEvent.KEYCODE_TAB -> sendText(TerminalKeys.tab(mods))
            KeyEvent.KEYCODE_ESCAPE -> sendText(TerminalKeys.escape(mods))
            KeyEvent.KEYCODE_DPAD_UP -> sendBytes(em?.cursorKey('A', mods) ?: TerminalKeys.cursor('A', mods, false).toByteArray(Charsets.US_ASCII))
            KeyEvent.KEYCODE_DPAD_DOWN -> sendBytes(em?.cursorKey('B', mods) ?: TerminalKeys.cursor('B', mods, false).toByteArray(Charsets.US_ASCII))
            KeyEvent.KEYCODE_DPAD_RIGHT -> sendBytes(em?.cursorKey('C', mods) ?: TerminalKeys.cursor('C', mods, false).toByteArray(Charsets.US_ASCII))
            KeyEvent.KEYCODE_DPAD_LEFT -> sendBytes(em?.cursorKey('D', mods) ?: TerminalKeys.cursor('D', mods, false).toByteArray(Charsets.US_ASCII))
            KeyEvent.KEYCODE_MOVE_HOME -> sendText(TerminalKeys.home(mods))
            KeyEvent.KEYCODE_MOVE_END -> sendText(TerminalKeys.end(mods))
            KeyEvent.KEYCODE_PAGE_UP -> sendText(TerminalKeys.tilde(5, mods))
            KeyEvent.KEYCODE_PAGE_DOWN -> sendText(TerminalKeys.tilde(6, mods))
            KeyEvent.KEYCODE_INSERT -> sendText(TerminalKeys.tilde(2, mods))
            else -> handled = false
        }
        if (handled) {
            consumeLatch()
            return true
        }

        // Some keyboards report no character at all for a Ctrl chord. The code
        // still names the letter, so the chord is built from that instead of
        // being dropped on the floor.
        val cp = if (event.unicodeChar != 0) {
            event.unicodeChar
        } else {
            (if (event.keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
                'a'.code + (event.keyCode - KeyEvent.KEYCODE_A)
            } else {
                return false
            })
        }
        sendText(TerminalKeys.typed(String(Character.toChars(cp)), mods))
        consumeLatch()
        return true
    }

    private fun consumeLatch() {
        if (latchedCtrl || latchedAlt || latchedShift) {
            latchedCtrl = false
            latchedAlt = false
            latchedShift = false
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
     * Keep the grid's keyboard up after a tap on the chrome (Ctrl, Alt, a key
     * chip), but only when the grid owns the IME and it was already up. A key
     * chip is a terminal key, not a text field: tapping Esc or an arrow must not
     * pull the IME over the grid. The command field has a keyboard of its own
     * that it keeps while it is the focused view.
     */
    fun keepSoftKeyboardIfShown() {
        // Only the grid's own keyboard is ours to keep. When the command field
        // owns it, taking focus back to the grid is exactly what makes the
        // system hide the field's IME and the restore below show it again: the
        // keyboard shut and reopened on every extra key pressed into the field.
        if (!isFocused) return
        val insets = ViewCompat.getRootWindowInsets(this) ?: return
        if (!insets.isVisible(WindowInsetsCompat.Type.ime())) return
        keepSoftKeyboard()
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
            // Through the same door as the hardware DEL key, so a latched Ctrl
            // makes the IME's Backspace the word-erase chord too.
            view.deleteBackward(beforeLength)
            if (afterLength > 0) view.sendRaw(TerminalKeys.tilde(3, KeyMods.NONE))
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) return view.handleKey(event)
            return true
        }
    }
}
