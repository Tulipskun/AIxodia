package com.tulipskun.aixodia.term

/** How many extra history rows may accumulate before a batched trim. */
private const val SCROLLBACK_SLACK = 256

/**
 * One row of cells. The fields are parallel arrays so a screenful is a handful
 * of allocations and drawing a row is a tight loop, not a walk over objects.
 *
 * A wide character occupies its cell with width 2 and the cell after it is left
 * as a continuation (a zero char). Combining marks cannot live in a single int,
 * so the few cells that have any keep their grapheme in [combining]; everything
 * else draws straight from [chars].
 */
class TerminalRow(columns: Int) {
    var columns = columns
        private set
    var chars = IntArray(columns) { SPACE }
    var width = ByteArray(columns) { 1 }
    var fg = IntArray(columns) { TerminalColors.DEFAULT_FG }
    var bg = IntArray(columns) { TerminalColors.DEFAULT_BG }
    var flags = ByteArray(columns)

    /** True when this row wrapped into the next, for copy and reflow. */
    var wrapped = false

    /**
     * The rightmost cell the shell has written, or -1 for a blank row. A
     * wrapped row copies its full width so a space at the break survives; this
     * keeps that width honest after a rotation widens the row, where the new
     * cells were never written and must not become spaces in the clipboard.
     */
    var lastWritten = -1
        internal set

    private var combining: HashMap<Int, StringBuilder>? = null

    fun resize(newColumns: Int) {
        if (newColumns == columns) return
        val oldChars = chars
        val oldWidth = width
        val oldFg = fg
        val oldBg = bg
        val oldFlags = flags
        chars = IntArray(newColumns) { SPACE }
        width = ByteArray(newColumns) { 1 }
        fg = IntArray(newColumns) { TerminalColors.DEFAULT_FG }
        bg = IntArray(newColumns) { TerminalColors.DEFAULT_BG }
        flags = ByteArray(newColumns)
        val keep = minOf(newColumns, columns)
        System.arraycopy(oldChars, 0, chars, 0, keep)
        System.arraycopy(oldWidth, 0, width, 0, keep)
        System.arraycopy(oldFg, 0, fg, 0, keep)
        System.arraycopy(oldBg, 0, bg, 0, keep)
        System.arraycopy(oldFlags, 0, flags, 0, keep)
        combining?.keys?.removeAll { it >= newColumns }
        columns = newColumns
        lastWritten = minOf(lastWritten, newColumns - 1)
    }

    fun reset(fgColor: Int, bgColor: Int) {
        for (i in 0 until columns) {
            chars[i] = SPACE
            width[i] = 1
            fg[i] = fgColor
            bg[i] = bgColor
            flags[i] = 0
        }
        combining = null
        wrapped = false
        lastWritten = -1
    }

    fun isBlank(): Boolean {
        for (i in 0 until columns) {
            if (chars[i] != SPACE && chars[i] != 0) return false
        }
        return combining.isNullOrEmpty()
    }

    /** True when the cell holds a glyph (not a continuation or a space). */
    fun hasGlyph(col: Int): Boolean = chars[col] != 0 && chars[col] != SPACE

    fun setChar(col: Int, codePoint: Int, cellWidth: Int, fgColor: Int, bgColor: Int, attr: Int) {
        if (col < 0 || col >= columns) return
        clearCombining(col)
        chars[col] = codePoint
        width[col] = cellWidth.toByte()
        fg[col] = fgColor
        bg[col] = bgColor
        flags[col] = attr.toByte()
        lastWritten = maxOf(lastWritten, col + cellWidth - 1)
        // A wide glyph owns the cell after it; that cell draws nothing.
        if (cellWidth == 2 && col + 1 < columns) {
            clearCombining(col + 1)
            chars[col + 1] = 0
            width[col + 1] = 0
            fg[col + 1] = fgColor
            bg[col + 1] = bgColor
            flags[col + 1] = attr.toByte()
        }
    }

    fun clearCells(from: Int, to: Int, fgColor: Int, bgColor: Int) {
        val end = minOf(to, columns - 1)
        // A line cleared end to end no longer continues into the row below, so
        // copy must not glue whatever is written there next onto it.
        if (from <= 0 && end >= columns - 1) wrapped = false
        if (end >= lastWritten) lastWritten = minOf(lastWritten, from - 1)
        for (i in maxOf(0, from)..end) {
            clearCombining(i)
            chars[i] = SPACE
            width[i] = 1
            fg[i] = fgColor
            bg[i] = bgColor
            flags[i] = 0
        }
    }

    /** Fill [from,to] with raw char/attr values, used when copying rows. */
    fun clearCombining(col: Int) {
        combining?.let {
            if (it.remove(col) != null && it.isEmpty()) combining = null
        }
    }

    fun combiningAt(col: Int): CharSequence? = combining?.get(col)

    fun appendCombining(col: Int, codePoint: Int) {
        if (col < 0 || col >= columns) return
        val map = combining ?: HashMap<Int, StringBuilder>().also { combining = it }
        val sb = map.getOrPut(col) { StringBuilder() }
        sb.appendCodePoint(codePoint)
    }

    fun copyFrom(other: TerminalRow) {
        if (columns != other.columns) resize(other.columns)
        System.arraycopy(other.chars, 0, chars, 0, columns)
        System.arraycopy(other.width, 0, width, 0, columns)
        System.arraycopy(other.fg, 0, fg, 0, columns)
        System.arraycopy(other.bg, 0, bg, 0, columns)
        System.arraycopy(other.flags, 0, flags, 0, columns)
        wrapped = other.wrapped
        lastWritten = other.lastWritten
        combining = other.combining?.let { HashMap(it) }
    }

    companion object {
        const val SPACE = 0x20
    }
}

/**
 * A screenful of rows plus the scrollback above it. The emulator keeps two of
 * these — the normal screen and the alternate one full-screen programs switch
 * to — and the same operations run on both.
 */
class TerminalBuffer(var columns: Int, var rows: Int, private val scrollbackLimit: Int) {
    val lines = ArrayList<TerminalRow>(rows)
    val scrollback = ArrayList<TerminalRow>()

    /**
     * Trimming one row at a time makes ArrayList shift every row that is left,
     * once per output line, at exactly the moment the history is full and the
     * shell is busiest. Allow a little slack and drop a chunk instead, so the
     * shift is paid roughly once per [SCROLLBACK_SLACK] lines.
     */
    private fun trimScrollback() {
        if (scrollback.size <= scrollbackLimit + SCROLLBACK_SLACK) return
        scrollback.subList(0, scrollback.size - scrollbackLimit).clear()
    }

    /** Normal screen keeps history; the alternate screen does not. */
    var keepScrollback = true

    var cursorCol = 0
    var cursorRow = 0
    var scrollTop = 0
    var scrollBottom = rows - 1

    private var defaultFg = TerminalColors.DEFAULT_FG
    private var defaultBg = TerminalColors.DEFAULT_BG

    init {
        for (i in 0 until rows) lines.add(TerminalRow(columns))
    }

    fun setDefaults(fgColor: Int, bgColor: Int) {
        defaultFg = fgColor
        defaultBg = bgColor
    }

    /**
     * The last [maxRows] rows as plain text, for the terminal's accessibility
     * description. A wide character owns its cell and leaves a zero in the next
     * one, so the zero is skipped and Thai or CJK reads without gaps.
     */
    fun spokenTail(maxRows: Int = 12): String {
        val from = (lines.size - maxRows).coerceAtLeast(0)
        val out = StringBuilder()
        for (i in from until lines.size) {
            val row = lines[i]
            val end = row.lastWritten
            if (end < 0) continue
            for (c in 0..end) {
                val cp = row.chars[c]
                if (cp != 0) out.appendCodePoint(cp)
            }
            out.append('\n')
        }
        return out.toString().trimEnd()
    }

    fun blankRow(): TerminalRow = TerminalRow(columns).also { it.reset(defaultFg, defaultBg) }

    fun reset() {
        for (row in lines) row.reset(defaultFg, defaultBg)
        scrollback.clear()
        cursorCol = 0
        cursorRow = 0
        scrollTop = 0
        scrollBottom = rows - 1
    }

    /** Content moves up: the top line goes to history and a blank appears last. */
    fun scrollUp(top: Int, bottom: Int, count: Int, fgColor: Int = defaultFg, bgColor: Int = defaultBg) {
        val n = count.coerceAtLeast(1)
        for (step in 0 until n) {
            val removed = lines[top]
            for (i in top until bottom) lines[i] = lines[i + 1]
            if (keepScrollback && top == 0) {
                // The row lives on in history, so a fresh blank takes its place
                // rather than resetting the row we just saved.
                scrollback.add(removed)
                trimScrollback()
                lines[bottom] = TerminalRow(columns).also { it.reset(fgColor, bgColor) }
            } else {
                removed.reset(fgColor, bgColor)
                lines[bottom] = removed
            }
        }
    }

    /** Content moves down: a blank appears at the top and the last line is lost. */
    fun scrollDown(top: Int, bottom: Int, count: Int, fgColor: Int = defaultFg, bgColor: Int = defaultBg) {
        val n = count.coerceAtLeast(1)
        for (step in 0 until n) {
            val removed = lines[bottom]
            for (i in bottom downTo top + 1) lines[i] = lines[i - 1]
            removed.reset(fgColor, bgColor)
            lines[top] = removed
        }
    }

    fun eraseInLine(row: Int, from: Int, to: Int, fgColor: Int, bgColor: Int) {
        lines[row].clearCells(from, to, fgColor, bgColor)
    }

    fun eraseInDisplay(mode: Int, fgColor: Int, bgColor: Int) {
        val last = columns - 1
        when (mode) {
            0 -> { // cursor to end
                lines[cursorRow].clearCells(cursorCol, last, fgColor, bgColor)
                for (r in cursorRow + 1 until rows) lines[r].clearCells(0, last, fgColor, bgColor)
            }
            1 -> { // start to cursor
                for (r in 0 until cursorRow) lines[r].clearCells(0, last, fgColor, bgColor)
                lines[cursorRow].clearCells(0, cursorCol, fgColor, bgColor)
            }
            2, 3 -> for (r in 0 until rows) lines[r].clearCells(0, last, fgColor, bgColor)
        }
    }

    fun insertChars(row: Int, col: Int, count: Int, fgColor: Int, bgColor: Int) {
        if (row !in 0 until rows) return
        val line = lines[row]
        val n = count.coerceAtMost(columns - col)
        for (i in columns - 1 downTo col + n) {
            line.chars[i] = line.chars[i - n]; line.width[i] = line.width[i - n]
            line.fg[i] = line.fg[i - n]; line.bg[i] = line.bg[i - n]; line.flags[i] = line.flags[i - n]
        }
        if (line.lastWritten >= col) line.lastWritten = minOf(columns - 1, line.lastWritten + n)
        line.clearCells(col, col + n - 1, fgColor, bgColor)
        // continuation cells introduced at the edge are not worth repairing;
        // the next full redraw settles them.
        line.clearCombining(col)
    }

    fun deleteChars(row: Int, col: Int, count: Int, fgColor: Int, bgColor: Int) {
        if (row !in 0 until rows) return
        val line = lines[row]
        val n = count.coerceAtMost(columns - col)
        for (i in col until columns - n) {
            line.chars[i] = line.chars[i + n]; line.width[i] = line.width[i + n]
            line.fg[i] = line.fg[i + n]; line.bg[i] = line.bg[i + n]; line.flags[i] = line.flags[i + n]
        }
        if (line.lastWritten >= col) line.lastWritten = maxOf(col - 1, line.lastWritten - n)
        line.clearCells(columns - n, columns - 1, fgColor, bgColor)
        line.clearCombining(columns - n)
    }

    fun insertLines(row: Int, count: Int, fgColor: Int, bgColor: Int) {
        if (row !in scrollTop..scrollBottom) return
        val n = count.coerceAtMost(scrollBottom - row + 1)
        scrollDown(row, scrollBottom, n, fgColor, bgColor)
    }

    fun deleteLines(row: Int, count: Int, fgColor: Int, bgColor: Int) {
        if (row !in scrollTop..scrollBottom) return
        val n = count.coerceAtMost(scrollBottom - row + 1)
        scrollUp(row, scrollBottom, n, fgColor, bgColor)
    }

    /**
     * Resize the screen, holding its bottom edge still.
     *
     * A terminal is read from the bottom: the prompt is the last row and output
     * leaves off the top. So a screen that gains rows puts them *above* the
     * content — taking the newest lines back out of the scrollback first, and
     * blank space only once the history is spent — and a screen that loses rows
     * loses them off the top. Adding the new rows underneath instead leaves the
     * prompt floating in the middle of a taller screen with a band of nothing
     * below it, which is what a keyboard opening and closing looked like: the
     * page had apparently scrolled itself back to the top.
     *
     * The cursor travels with the rows the content gained or lost so it keeps the
     * same distance from the bottom edge, which is the line the shell believes it
     * is still writing on.
     *
     * The alternate screen is a program's own canvas. It repaints from its
     * top-left corner the moment the size arrives, so it keeps the top anchor and
     * nothing is moved between it and a history it does not have.
     */
    fun resize(newColumns: Int, newRows: Int) {
        columns = newColumns
        rows = newRows
        for (row in lines) row.resize(newColumns)
        // History rows are now painted, so they must follow a rotation too;
        // otherwise an old-width row stops short of the right edge.
        for (row in scrollback) row.resize(newColumns)
        var shifted = 0
        if (keepScrollback) {
            while (lines.size < newRows) {
                val incoming = if (scrollback.isNotEmpty()) {
                    scrollback.removeAt(scrollback.lastIndex)
                } else {
                    blankRow()
                }
                lines.add(0, incoming)
                shifted++
            }
            while (lines.size > newRows) {
                scrollback.add(lines.removeAt(0))
                trimScrollback()
                shifted--
            }
        } else {
            while (lines.size < newRows) lines.add(blankRow())
            while (lines.size > newRows) lines.removeAt(lines.lastIndex)
        }
        if (shifted != 0) cursorRow = (cursorRow + shifted).coerceIn(0, newRows - 1)
        scrollTop = 0
        scrollBottom = newRows - 1
        cursorRow = cursorRow.coerceIn(0, newRows - 1)
        cursorCol = cursorCol.coerceIn(0, newColumns - 1)
    }
}
