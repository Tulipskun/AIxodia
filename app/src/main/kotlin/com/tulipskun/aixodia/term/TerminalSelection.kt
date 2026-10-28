package com.tulipskun.aixodia.term

/**
 * Reading a selection out of the cell grid. Selection drawing and copying both
 * stop at the last visible cell of each row: the buffer always stores a full
 * screen width, and a browser-like selection must not carry the blank right
 * half of the terminal into the clipboard.
 */
internal object TerminalSelection {

    /**
     * How close to the right edge a row must end before it may be taken as
     * continuing into the next one. A soft-wrapped row is known to the
     * emulator, but a full-screen program wraps its own text and draws every
     * row separately; there the edge is the only evidence a line went on.
     */
    private const val CONTINUES_SLACK = 8

    fun firstTextCol(row: TerminalRow): Int {
        for (col in 0 until row.columns) if (row.hasGlyph(col)) return col
        return -1
    }

    fun lastTextCol(row: TerminalRow): Int {
        for (col in row.columns - 1 downTo 0) if (row.hasGlyph(col)) return col
        return -1
    }

    /**
     * Everything between the two ends, with every row cut at its last visible
     * cell. [firstRow] and [firstCol] must be before [lastRow] and [lastCol].
     * Blank rows inside the selection become newlines; blank rows at the ends
     * are dropped.
     *
     * A row that continues the one above joins it without a break. That is a
     * soft wrap the emulator saw — such a row keeps its full width, because its
     * last cell is real text, often the space a word was broken at — or a row
     * a full-screen program wrapped itself: one that runs to the right edge and
     * is followed by an indented line. The program's left padding is layout,
     * not text, so it is dropped from those continuations; the first selected
     * row tells how wide it is.
     */
    fun text(
        buffer: TerminalBuffer,
        firstRow: Int,
        firstCol: Int,
        lastRow: Int,
        lastCol: Int,
    ): String {
        if (buffer.lines.isEmpty()) return ""
        val first = firstRow.coerceIn(0, buffer.lines.size - 1)
        val last = lastRow.coerceIn(0, buffer.lines.size - 1)

        fun fromOf(r: Int): Int =
            (if (r == first) firstCol else 0)
                .coerceIn(0, buffer.lines[r].columns - 1)

        fun endOf(r: Int): Int =
            (if (r == last) lastCol else buffer.lines[r].columns - 1)
                .coerceIn(0, buffer.lines[r].columns - 1)

        // The block's left padding, read from the first selected row: even when
        // the selection starts at the text, the cells before it are still there.
        var pad = 0
        run {
            val head = buffer.lines[first]
            if (head.lastWritten >= 0) {
                while (pad < head.columns && head.chars[pad] == TerminalRow.SPACE) pad++
            }
            if (pad >= head.columns) pad = 0
        }

        // Does row r run on into r+1?
        val continues = BooleanArray(last - first + 1)
        for (r in first until last) {
            val row = buffer.lines[r]
            if (row.columns == 0) continue
            if (row.wrapped) {
                continues[r - first] = true
                continue
            }
            // A program-wrapped row is only believed when the block is padded:
            // full-bleed output that happens to reach the edge is more likely to
            // be a table or a long command line than a paragraph.
            if (pad == 0) continue
            // A full line of the block ends one padding short of the edge, so
            // the padding bounds how far from it the cut can fall.
            val slack = maxOf(CONTINUES_SLACK, pad)
            if (row.lastWritten < row.columns - 1 - slack) continue
            val next = buffer.lines[r + 1]
            if (next.columns == 0 || next.lastWritten < 0) continue
            val end = endOf(r + 1)
            var lead = 0
            while (lead <= end && next.chars[lead] == TerminalRow.SPACE) lead++
            if (lead in 1..end) continues[r - first] = true
        }

        val sb = StringBuilder()
        for (r in first..last) {
            val row = buffer.lines[r]
            if (row.columns == 0) continue
            val from = fromOf(r)
            val end = endOf(r)
            // A wrapped row keeps every cell the shell wrote, including a space
            // that fell on the break; a rotation may have widened the row since,
            // and those cells were never written.
            val to = if (row.wrapped) {
                minOf(end, maxOf(lastTextCol(row), row.lastWritten))
            } else {
                minOf(end, lastTextCol(row))
            }
            val line = StringBuilder()
            if (to >= from) {
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
            }
            // The layout indent of a program-wrapped line is not text. A soft
            // wrap starts at column zero, so only the former can carry it.
            var start = 0
            if (r > first && continues[r - 1 - first] && !buffer.lines[r - 1].wrapped) {
                while (start < line.length && start < pad && line[start] == ' ') start++
            }
            val body = line.substring(start)
            sb.append(if (row.wrapped) body else body.trimEnd(' '))
            if (r < last && !continues[r - first]) sb.append('\n')
        }
        return sb.toString().trim('\n')
    }
}
