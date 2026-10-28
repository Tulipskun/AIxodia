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
     * last cell is real text, often the space a word was broken at — or a
     * paragraph a full-screen program wrapped itself. The program marks
     * nothing: its rows are hard breaks inside a padded block, so the block
     * itself is the evidence. When at least two of its lines end at the same
     * column and another ends short, the program cut at that column,
     * mid-word, and those lines join as they are. Otherwise the cut fell on a
     * space, which the program dropped; the block's prose lines join with one
     * space put back, unless the program carried it to the next line. Rows
     * with a column-aligned gap (two spaces in a row) and rows that open a
     * list, heading, quote or table are not prose and end the run. The
     * padding is layout, not text, so it is dropped from continuations; the
     * first selected row tells how wide it is.
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
        // A cut at a space drops the space; when the program did not carry it
        // to the next line, the join puts one back.
        val spaceBefore = BooleanArray(last - first + 1)
        for (r in first until last) {
            if (buffer.lines[r].wrapped) continues[r - first] = true
        }
        // A program-wrapped row is only believed inside a padded block:
        // full-bleed output is more likely a table or a long command line.
        if (pad > 0 && last > first) {
            // A line of the block starts where the block's text starts, or one
            // cell further right when the program carried the break space over.
            fun isBlock(r: Int): Boolean {
                val lead = firstTextCol(buffer.lines[r])
                return lead == pad || lead == pad + 1
            }

            // Tables and code line their columns up with runs of spaces; prose
            // keeps single ones.
            fun isProse(row: TerminalRow): Boolean {
                val from = firstTextCol(row)
                val to = lastTextCol(row)
                var col = from
                while (col < to) {
                    if (row.chars[col] == TerminalRow.SPACE &&
                        row.chars[col + 1] == TerminalRow.SPACE
                    ) {
                        return false
                    }
                    col++
                }
                return true
            }

            // A line that opens a list, heading, quote or table starts a new
            // element: the line above it is not its continuation.
            fun opensElement(row: TerminalRow): Boolean {
                val col = firstTextCol(row)
                if (col < 0) return false
                val cp = row.chars[col]
                if (cp == '-'.code || cp == '*'.code || cp == '+'.code ||
                    cp == '•'.code || cp == '>'.code || cp == '|'.code ||
                    cp == '#'.code || cp in 0x2500..0x257F
                ) {
                    return true
                }
                if (cp in '0'.code..'9'.code) {
                    var c = col
                    while (c < row.columns && row.chars[c] in '0'.code..'9'.code) c++
                    return c < row.columns &&
                        (row.chars[c] == '.'.code || row.chars[c] == ')'.code)
                }
                return false
            }

            val block = (first..last).filter { isBlock(it) }
            val ends = block.map { buffer.lines[it].lastWritten }.filter { it >= 0 }
            val maxEnd = ends.maxOrNull() ?: -1
            val full = ends.count { it == maxEnd }
            // Lines cut at the program's text edge stop at one column, and the
            // paragraph ends in a shorter line. Every line reaching that column
            // with none shorter is aligned content, a table — no wrap at all.
            val cellCut = full >= 2 && ends.any { it < maxEnd }
            val aligned = full >= 2 && !cellCut

            if (!aligned) {
                for (r in first until last) {
                    val row = buffer.lines[r]
                    if (row.columns == 0 || row.wrapped) continue
                    if (!isBlock(r) || !isBlock(r + 1)) continue
                    val next = buffer.lines[r + 1]
                    val lead = firstTextCol(next)
                    if (lead > endOf(r + 1)) continue
                    if (!isProse(row) || !isProse(next) || opensElement(next)) continue
                    if (cellCut) {
                        // Cut mid-word: only the lines ending at the column
                        // were cut, and they join as they are.
                        if (row.lastWritten != maxEnd) continue
                        continues[r - first] = true
                    } else {
                        // Cut at a space, which the program dropped.
                        continues[r - first] = true
                        spaceBefore[r - first + 1] = lead == pad
                    }
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
                if (spaceBefore[r - first]) sb.append(' ')
            }
            val body = line.substring(start)
            sb.append(if (row.wrapped) body else body.trimEnd(' '))
            if (r < last && !continues[r - first]) sb.append('\n')
        }
        return sb.toString().trim('\n')
    }
}
