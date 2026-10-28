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
     * are dropped. A soft-wrapped row keeps its full width — its last cell is
     * real text, often the space a word was broken at — and is joined to the
     * row below without a break.
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
        val sb = StringBuilder()
        for (r in first..last) {
            val row = buffer.lines[r]
            if (row.columns == 0) continue
            val from = (if (r == first) firstCol else 0).coerceIn(0, row.columns - 1)
            val end = (if (r == last) lastCol else row.columns - 1).coerceIn(0, row.columns - 1)
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
            sb.append(if (row.wrapped) line.toString() else line.toString().trimEnd(' '))
            // A wrapped row is one logical line: join it without a break.
            if (r < last && !row.wrapped) sb.append('\n')
        }
        return sb.toString().trim('\n')
    }
}
