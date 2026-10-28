package com.tulipskun.aixodia.term

/**
 * Reading a selection out of the cell grid. Selection drawing and copying both
 * stop at the last visible cell of each row: the buffer always stores a full
 * screen width, and a browser-like selection must not carry the blank right
 * half of the terminal into the clipboard.
 */
internal object TerminalSelection {

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
     * a full-screen program wrapped itself. The program leaves no mark: its
     * rows are hard breaks carrying the block's left padding. The right margin
     * is read from the block itself — the column where at least two of its
     * lines end, in a block that also ends in a shorter line — and a row
     * ending there joins an indented row below. The padding is layout, not
     * text, so it is dropped from those continuations; the first selected row
     * tells how wide it is.
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
            if (buffer.lines[r].wrapped) continues[r - first] = true
        }
        // A program-wrapped row is only believed when the block is padded:
        // full-bleed output is more likely a table or a long command line than
        // a paragraph.
        if (pad > 0) {
            // Where the block's text area stops, read from the rows: a column
            // where at least two of its lines end. One line ending there proves
            // nothing — a heading is often the longest line of its block — and
            // a block whose every line reaches the same column is aligned
            // content, a table, not prose. So the margin only counts when some
            // line ends short of it, the ragged last line of a paragraph.
            val ends = HashMap<Int, Int>()
            for (r in first..last) {
                val row = buffer.lines[r]
                if (row.columns == 0 || row.lastWritten < 0) continue
                if (firstTextCol(row) < pad) continue
                ends[row.lastWritten] = (ends[row.lastWritten] ?: 0) + 1
            }
            var margin = -1
            for ((end, count) in ends) if (count >= 2 && end > margin) margin = end
            if (ends.keys.none { it < margin }) margin = -1
            if (margin >= 0) {
                for (r in first until last) {
                    val row = buffer.lines[r]
                    if (row.columns == 0 || row.wrapped) continue
                    // The program cuts a line where its text area stops; one
                    // that stops anywhere else ended by itself.
                    if (row.lastWritten != margin) continue
                    val next = buffer.lines[r + 1]
                    if (next.columns == 0) continue
                    val lead = firstTextCol(next)
                    if (lead < pad || lead > endOf(r + 1)) continue
                    continues[r - first] = true
                }
            }
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
