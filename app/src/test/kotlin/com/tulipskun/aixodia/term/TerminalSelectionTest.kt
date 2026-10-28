package com.tulipskun.aixodia.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSelectionTest {

    private fun buffer(rows: Int, cols: Int = 20): TerminalBuffer =
        TerminalBuffer(cols, rows, scrollbackLimit = 0)

    private class Sink : TerminalOutput {
        override fun write(bytes: ByteArray) = Unit
        override fun onScreenChanged() = Unit
        override fun onTitleChanged(title: String) = Unit
    }

    /**
     * A command longer than the screen is one logical line. The break falls on
     * a space here, so only a copy that keeps the full wrapped row can put the
     * words back together with their spacing intact.
     */
    @Test
    fun aSoftWrapCopiesAsOneLine() {
        val emulator = TerminalEmulator(cols = 20, rows = 4, output = Sink())
        emulator.write("hello world this is a long line".toByteArray())
        val buffer = emulator.buffer
        assertTrue("the first row wrapped", buffer.lines[0].wrapped)
        assertFalse("the second row did not", buffer.lines[1].wrapped)

        assertEquals(
            "hello world this is a long line",
            TerminalSelection.text(buffer, 0, 0, 1, buffer.columns - 1),
        )
    }

    @Test
    fun clearingARowDropsItsWrap() {
        val buffer = buffer(2)
        buffer.line(0).wrapped = true
        buffer.line(0).clearCells(
            0,
            buffer.columns - 1,
            TerminalColors.DEFAULT_FG,
            TerminalColors.DEFAULT_BG,
        )
        assertFalse("an erased row no longer continues below", buffer.line(0).wrapped)
    }

    /**
     * The text a full-screen program drew in a script without spaces: it cuts
     * mid-word where its text area stops, so two lines end at one column and
     * the last is shorter. The copy joins exactly those lines; the space that
     * fell on the first break is real text and must not be lost.
     */
    @Test
    fun aProgramWrappedParagraphCopiesAsOneParagraph() {
        val part1 = "ข้อควรทราบ: เครื่องนี้ไม่มีมือถือต่อและไม่มี KVM จึงยืนยันได้แค่ระดับ"
        val part2 = "MotionEvent/unit test เท่านั้น ฝากลองบนเครื่องจริงอีกรอบว"
        val part3 = "าทิศทางและแป้นพิมพ์ตรงตามที่ต้องการไหมครับ"
        // The lines end three cells short of the edge, the padding's width —
        // exactly how the screen the sample was copied from was laid out.
        val buffer = buffer(3, cols = 57)
        buffer.put(0, 0, "   $part1")
        buffer.put(1, 0, "    $part2")
        buffer.put(2, 0, "   $part3")

        assertEquals(
            "$part1 $part2$part3",
            TerminalSelection.text(buffer, 0, 3, 2, buffer.columns - 1),
        )
    }

    /**
     * The same paragraph broken at the spaces between its words: the lines
     * end at different columns, so the program cut at a space and dropped it.
     * Copy puts the paragraph back with those spaces.
     */
    @Test
    fun aWordWrappedParagraphCopiesAsOneParagraph() {
        val part1 = "ข้อจำกัดที่เหลือ (โดยธรรมชาติของการเดา): ย่อหน้าที่มีแค่ 2"
        val part2 = "บรรทัดและบรรทัดที่สองสั้น จะยังไม่ต่อให้"
        val part3 = "เพราะหาขอบไม่ได้จากสองบรรทัด —"
        val part4 = "ลองใช้บนเครื่องจริงแล้วเจอเคสไหนบอกได้ครับ"
        // The screen the sample was copied from: the first line fills it.
        val buffer = buffer(4, cols = 52)
        buffer.put(0, 0, "   $part1")
        buffer.put(1, 0, "   $part2")
        buffer.put(2, 0, "   $part3")
        buffer.put(3, 0, "   $part4")

        assertEquals(
            "$part1 $part2 $part3 $part4",
            TerminalSelection.text(buffer, 0, 3, 3, buffer.columns - 1),
        )
    }

    /** The program carried the break space over, so no second one is added. */
    @Test
    fun aCarriedSpaceIsNotDoubled() {
        val buffer = buffer(2, cols = 40)
        buffer.put(0, 0, "   alpha beta")
        buffer.put(1, 0, "    gamma")

        assertEquals(
            "alpha beta gamma",
            TerminalSelection.text(buffer, 0, 3, 1, buffer.columns - 1),
        )
    }

    @Test
    fun aListItemStartsANewLine() {
        val buffer = buffer(2, cols = 40)
        buffer.put(0, 0, "   the paragraph above")
        buffer.put(1, 0, "   - item one")

        assertEquals(
            "the paragraph above\n   - item one",
            TerminalSelection.text(buffer, 0, 3, 1, buffer.columns - 1),
        )
    }

    /**
     * Two rows end at column 19, so that is the block's margin, and a shorter
     * line ends below it. The first cut row is followed by text at column 0:
     * not a line of the block, so the break stays. The second cut row is
     * followed by an indented line, which does join.
     */
    @Test
    fun aRowAtTheMarginIsNotJoinedToAnUnindentedOne() {
        val buffer = buffer(4, cols = 20)
        buffer.put(0, 0, "  " + "y".repeat(18))
        buffer.put(1, 0, "next")
        buffer.put(2, 0, "  " + "z".repeat(18))
        buffer.put(3, 0, "   tail")

        assertEquals(
            "y".repeat(18) + "\nnext\n  " + "z".repeat(18) + " tail",
            TerminalSelection.text(buffer, 0, 2, 3, buffer.columns - 1),
        )
    }

    /**
     * A block whose lines all reach the same column, with none shorter, is
     * aligned content — a table or a column-aligned list — not a wrapped
     * paragraph, and the gaps between its columns are not prose either.
     */
    @Test
    fun alignedRowsAreNotAWrapColumn() {
        val buffer = buffer(2, cols = 64)
        buffer.put(0, 0, "   one  x")
        buffer.put(1, 0, "   two  y")

        assertEquals(
            "one  x\n   two  y",
            TerminalSelection.text(buffer, 0, 3, 1, buffer.columns - 1),
        )
    }

    /**
     * Without a left padding there is no block whose indentation could be
     * stripped, so an edge-to-edge row is more likely a table than a wrap.
     */
    @Test
    fun aFullBleedBlockKeepsItsBreaks() {
        val buffer = buffer(2, cols = 64)
        buffer.put(0, 0, "x".repeat(64))
        buffer.put(1, 0, "    MotionEvent")

        assertEquals(
            "x".repeat(64) + "\n    MotionEvent",
            TerminalSelection.text(buffer, 0, 0, 1, buffer.columns - 1),
        )
    }

    private fun TerminalBuffer.put(row: Int, col: Int, text: String, width: Int = 0) {
        var at = col
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val w = if (width != 0) width else WcWidth.width(cp)
            if (w == 0) {
                lines[row].appendCombining(at - 1, cp)
            } else {
                lines[row].setChar(
                    at,
                    cp,
                    w,
                    TerminalColors.DEFAULT_FG,
                    TerminalColors.DEFAULT_BG,
                    0,
                )
                at += w
            }
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
