package com.tulipskun.aixodia.term

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
                while (scrollback.size > scrollbackLimit) scrollback.removeAt(0)
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

    fun resize(newColumns: Int, newRows: Int) {
        columns = newColumns
        rows = newRows
        for (row in lines) row.resize(newColumns)
        while (lines.size < newRows) lines.add(TerminalRow(newColumns).also { it.reset(defaultFg, defaultBg) })
        while (lines.size > newRows) {
            if (keepScrollback) {
                scrollback.add(lines.removeAt(0))
                while (scrollback.size > scrollbackLimit) scrollback.removeAt(0)
            } else {
                lines.removeAt(lines.size - 1)
            }
        }
        scrollTop = 0
        scrollBottom = newRows - 1
        cursorRow = cursorRow.coerceIn(0, newRows - 1)
        cursorCol = cursorCol.coerceIn(0, newColumns - 1)
    }
}
