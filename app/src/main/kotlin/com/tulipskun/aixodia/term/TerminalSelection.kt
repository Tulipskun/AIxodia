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
     *
     * A URL is one token even when it crosses rows: a line with no spaces
     * that carries a scheme, followed by another line with no spaces, joins
     * without the space the rule above would put back.
     *
     * When every selected row belongs to a box — a vertical border, a rule,
     * or empty — the copy is the card's content. Borders and the padding that
     * centers the text are chrome, not text, so they are dropped; content
     * rows keep their own breaks and blank rows stay blank. Only a broken URL
     * joins. A paragraph the program wrapped inside a card is not reflowed,
     * because each card row is padded on its own and leaves no cut column to
     * trust.
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

        cardText(buffer, first, firstCol, last, lastCol)?.let { return it }

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

            fun rowString(r: Int): String {
                val row = buffer.lines[r]
                return rowText(row, firstTextCol(row), lastTextCol(row))
            }

            // Tables and code line their columns up with runs of spaces; prose
            // keeps single ones.
            fun isProseRow(r: Int): Boolean = isProse(rowString(r))

            // A line that opens a list, heading, quote or table starts a new
            // element: the line above it is not its continuation.
            fun opensRow(r: Int): Boolean = opensElement(rowString(r))

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
                    val lead = firstTextCol(buffer.lines[r + 1])
                    if (lead > endOf(r + 1)) continue
                    if (!isProseRow(r) || !isProseRow(r + 1) || opensRow(r + 1)) continue
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
        var previous: String? = null
        for (r in first..last) {
            val row = buffer.lines[r]
            if (row.columns == 0) continue
            val end = endOf(r)
            // A wrapped row keeps every cell the shell wrote, including a space
            // that fell on the break; a rotation may have widened the row since,
            // and those cells were never written.
            val to = if (row.wrapped) {
                minOf(end, maxOf(lastTextCol(row), row.lastWritten))
            } else {
                minOf(end, lastTextCol(row))
            }
            var body = rowText(row, fromOf(r), to)
            // The layout indent of a program-wrapped line is not text. A soft
            // wrap starts at column zero, so only the former can carry it.
            var start = 0
            if (r > first && continues[r - 1 - first] && !buffer.lines[r - 1].wrapped) {
                while (start < body.length && start < pad && body[start] == ' ') start++
                if (spaceBefore[r - first] && !isLink(previous.orEmpty(), body)) sb.append(' ')
            }
            body = body.substring(start)
            if (!row.wrapped) body = body.trimEnd(' ')
            sb.append(body)
            previous = body
            if (r < last && !continues[r - first]) sb.append('\n')
        }
        return sb.toString().trim('\n')
    }

    /**
     * The content of a box that fills the whole selection, or null when any
     * selected row lies outside one. A card row starts with a vertical border
     * after the layout indent; a rule row holds nothing but box drawing; any
     * other row means the selection is not just a card.
     */
    private fun cardText(
        buffer: TerminalBuffer,
        first: Int,
        firstCol: Int,
        last: Int,
        lastCol: Int,
    ): String? {
        val content = ArrayList<String>(last - first + 1)
        val wrapped = ArrayList<Boolean>(last - first + 1)
        var bordered = 0
        for (r in first..last) {
            val row = buffer.lines[r]
            val lead = if (row.columns == 0) -1 else firstTextCol(row)
            if (lead < 0) {
                content += ""
                wrapped += false
                continue
            }
            val cutTo = (if (r == last) lastCol else row.columns - 1)
                .coerceIn(0, row.columns - 1)
            val cutFrom = (if (r == first) firstCol else 0)
                .coerceIn(0, row.columns - 1)
            if (isVerticalBorder(row.chars[lead])) {
                bordered++
                content += cardRow(rowText(row, maxOf(cutFrom, lead), cutTo))
                wrapped += row.wrapped
            } else if (isBoxOnly(row, lead, lastTextCol(row))) {
                content += ""
                wrapped += false
            } else {
                return null
            }
        }
        if (bordered == 0) return null
        return joinCard(content, wrapped)
    }

    /**
     * Card rows, top to bottom. Blank rows separate paragraphs; a row the
     * emulator wrapped, or one that continues a broken URL, joins the row
     * above; everything else keeps its own line, because card rows are padded
     * one by one and their cut column cannot be trusted.
     */
    private fun joinCard(lines: List<String>, wrapped: List<Boolean>): String {
        val sb = StringBuilder()
        var blanks = 0
        var previous: String? = null
        for (i in lines.indices) {
            val line = lines[i].trim(' ')
            if (line.isEmpty()) {
                if (sb.isNotEmpty()) blanks++
                continue
            }
            if (sb.isNotEmpty()) {
                when {
                    blanks > 0 -> sb.append("\n".repeat(blanks + 1))
                    wrapped[i - 1] || isLink(previous.orEmpty(), line) -> Unit
                    else -> sb.append('\n')
                }
            }
            sb.append(line)
            previous = line
            blanks = 0
        }
        return sb.toString()
    }

    /** One card row without its borders and the padding that centers it. */
    private fun cardRow(raw: String): String {
        var s = raw.trim(' ')
        if (s.isEmpty()) return ""
        val head = s.codePointAt(0)
        if (isVerticalBorder(head)) s = s.substring(Character.charCount(head)).trim(' ')
        if (s.isEmpty()) return ""
        val tail = s.codePointBefore(s.length)
        if (isVerticalBorder(tail)) {
            s = s.substring(0, s.length - Character.charCount(tail)).trim(' ')
        }
        return s
    }

    /** A rule row: nothing but box drawing up to its last visible cell. */
    private fun isBoxOnly(row: TerminalRow, from: Int, to: Int): Boolean {
        var col = from
        while (col <= to) {
            val cp = row.chars[col]
            if (cp != 0 && cp != TerminalRow.SPACE && cp !in 0x2500..0x257F) return false
            col += maxOf(1, row.width[col].toInt())
        }
        return true
    }

    /** The vertical strokes of a box: │, ┃, ╎, ╏, ║. */
    private fun isVerticalBorder(cp: Int): Boolean =
        cp == 0x2502 || cp == 0x2503 || cp == 0x254E || cp == 0x254F || cp == 0x2551

    /** The text of one row between two columns, skipping wide-glyph tails. */
    private fun rowText(row: TerminalRow, from: Int, to: Int): String {
        if (from < 0 || to < from) return ""
        val sb = StringBuilder(to - from + 1)
        var col = from
        while (col <= to && col < row.columns) {
            val cp = row.chars[col]
            when {
                cp == 0 -> Unit // a wide glyph's continuation cell
                cp == TerminalRow.SPACE -> sb.append(' ')
                else -> {
                    sb.appendCodePoint(cp)
                    row.combiningAt(col)?.let { sb.append(it) }
                }
            }
            col += maxOf(1, row.width[col].toInt())
        }
        return sb.toString()
    }

    private fun isProse(text: String): Boolean = "  " !in text

    private fun opensElement(text: String): Boolean {
        if (text.isEmpty()) return false
        val cp = text.codePointAt(0)
        if (cp == '-'.code || cp == '*'.code || cp == '+'.code ||
            cp == '•'.code || cp == '>'.code || cp == '|'.code ||
            cp == '#'.code || cp in 0x2500..0x257F
        ) {
            return true
        }
        if (cp in '0'.code..'9'.code) {
            var i = 0
            while (i < text.length && text[i] in '0'..'9') i++
            return i < text.length && (text[i] == '.' || text[i] == ')')
        }
        return false
    }

    /**
     * A URL split over two rows. Both rows must be single tokens — a line with
     * a space is prose, not the rest of a link — and the first must carry a
     * scheme, or the second must continue its query string.
     */
    private fun isLink(before: String, after: String): Boolean {
        val a = before.trim(' ')
        val b = after.trim(' ')
        if (a.isEmpty() || b.isEmpty() || ' ' in a || ' ' in b) return false
        if ("://" in a || a.startsWith("www.")) return true
        return (a.endsWith('?') || a.endsWith('&')) && '=' in b
    }
}
