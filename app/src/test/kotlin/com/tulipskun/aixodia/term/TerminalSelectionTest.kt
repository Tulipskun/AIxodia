package com.tulipskun.aixodia.term

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalSelectionTest {

    private fun buffer(rows: Int, cols: Int = 20): TerminalBuffer =
        TerminalBuffer(cols, rows, scrollbackLimit = 0)

    private fun TerminalBuffer.put(row: Int, col: Int, text: String, width: Int = 1) {
        var at = col
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            lines[row].setChar(
                at,
                cp,
                width,
                TerminalColors.DEFAULT_FG,
                TerminalColors.DEFAULT_BG,
                0,
            )
            at += width
            i += Character.charCount(cp)
        }
    }

    @Test
    fun textStopsAtTheLastVisibleCell() {
        val buffer = buffer(1)
        buffer.lines[0].let {
            it.setChar(0, 'h'.code, 1, TerminalColors.DEFAULT_FG, TerminalColors.DEFAULT_BG, 0)
            it.setChar(1, 'i'.code, 1, TerminalColors.DEFAULT_FG, TerminalColors.DEFAULT_BG, 0)
        }

        assertEquals(
            "hi",
            TerminalSelection.text(buffer, 0, 0, 0, buffer.columns - 1),
        )
    }

    @Test
    fun blankRowsInsideSelectionBecomeLineBreaks() {
        val buffer = buffer(3)
        buffer.put(0, 0, "one")
        buffer.put(2, 0, "two")

        assertEquals(
            "one\n\ntwo",
            TerminalSelection.text(buffer, 0, 0, 2, buffer.columns - 1),
        )
    }

    @Test
    fun wrappedRowsJoinWithoutABreak() {
        val buffer = buffer(2)
        buffer.line(0).let {
            it.setChar(0, 'h'.code, 1, TerminalColors.DEFAULT_FG, TerminalColors.DEFAULT_BG, 0)
            it.setChar(1, 'e'.code, 1, TerminalColors.DEFAULT_FG, TerminalColors.DEFAULT_BG, 0)
            it.setChar(2, 'l'.code, 1, TerminalColors.DEFAULT_FG, TerminalColors.DEFAULT_BG, 0)
            it.wrapped = true
        }
        buffer.line(1).let {
            it.setChar(0, 'l'.code, 1, TerminalColors.DEFAULT_FG, TerminalColors.DEFAULT_BG, 0)
            it.setChar(1, 'o'.code, 1, TerminalColors.DEFAULT_FG, TerminalColors.DEFAULT_BG, 0)
        }

        assertEquals(
            "hello",
            TerminalSelection.text(buffer, 0, 0, 1, buffer.columns - 1),
        )
    }

    @Test
    fun blankRowsAtTheEndsAreDropped() {
        val buffer = buffer(3)
        buffer.put(1, 0, "x")

        assertEquals(
            "x",
            TerminalSelection.text(buffer, 0, 0, 2, buffer.columns - 1),
        )
    }

    @Test
    fun wideGlyphIsCopiedOnce() {
        val buffer = buffer(1)
        buffer.put(0, 0, "あ", width = 2)

        assertEquals(
            "あ",
            TerminalSelection.text(buffer, 0, 0, 0, 1),
        )
    }

    @Test
    fun internalSpacesAreKept() {
        val buffer = buffer(1)
        for ((col, ch) in "a b".withIndex()) buffer.put(0, col, ch.toString())

        assertEquals(
            "a b",
            TerminalSelection.text(buffer, 0, 0, 0, buffer.columns - 1),
        )
    }
}

private fun TerminalBuffer.line(row: Int): TerminalRow = lines[row]
