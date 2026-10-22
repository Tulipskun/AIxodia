package com.tulipskun.aixodia.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Captures what the emulator sends back and counts redraws. */
private class RecordingOutput : TerminalOutput {
    val responses = StringBuilder()
    var redraws = 0
    var title = ""
    var clipboard: String? = null

    override fun write(bytes: ByteArray) {
        responses.append(String(bytes, Charsets.UTF_8))
    }

    override fun onScreenChanged() {
        redraws++
    }

    override fun onTitleChanged(title: String) {
        this.title = title
    }

    override fun onClipboard(text: String) {
        clipboard = text
    }
}

class TerminalEmulatorTest {

    private fun emulator(cols: Int = 20, rows: Int = 5): Pair<TerminalEmulator, RecordingOutput> {
        val out = RecordingOutput()
        return TerminalEmulator(cols, rows, out) to out
    }

    private fun TerminalEmulator.send(s: String) = write(s.toByteArray(Charsets.UTF_8))

    private fun TerminalEmulator.textAt(row: Int, col: Int): String {
        val cp = buffer.lines[row].chars[col]
        return if (cp == 0) "" else String(Character.toChars(cp))
    }

    @Test
    fun plainTextAdvancesTheCursor() {
        val (term, _) = emulator()
        term.send("hello")
        assertEquals("h", term.textAt(0, 0))
        assertEquals("o", term.textAt(0, 4))
        assertEquals(5, term.buffer.cursorCol)
        assertEquals(0, term.buffer.cursorRow)
    }

    @Test
    fun carriageReturnAndLineFeed() {
        val (term, _) = emulator()
        term.send("one\r\n")
        assertEquals(0, term.buffer.cursorCol)
        assertEquals(1, term.buffer.cursorRow)
    }

    @Test
    fun cursorPositionIsOneBased() {
        val (term, _) = emulator()
        term.send("\u001b[3;5H")
        assertEquals(4, term.buffer.cursorCol)
        assertEquals(2, term.buffer.cursorRow)
    }

    @Test
    fun eraseToEndOfLineBlanksTheTail() {
        val (term, _) = emulator()
        term.send("abcdef")
        term.send("\u001b[3G") // column 3
        term.send("\u001b[K")  // erase from the cursor (inclusive) to the end
        assertEquals("b", term.textAt(0, 1))
        assertEquals(" ", term.textAt(0, 2))
        assertEquals(" ", term.textAt(0, 5))
    }

    @Test
    fun sgrStoresResolvedColours() {
        val (term, _) = emulator()
        term.send("\u001b[31;44mX")
        val line = term.buffer.lines[0]
        assertEquals(TerminalColors.indexed(1), line.fg[0])
        assertEquals(TerminalColors.indexed(4), line.bg[0])
    }

    @Test
    fun trueColourSgr() {
        val (term, _) = emulator()
        term.send("\u001b[38;2;10;20;30mX")
        assertEquals(TerminalColors.rgb(10, 20, 30), term.buffer.lines[0].fg[0])
    }

    @Test
    fun thinAndWideGlyphsOccupyTheRightCells() {
        val (term, _) = emulator()
        term.send("a\u4e2db") // a, CJK '中' (wide), b
        val line = term.buffer.lines[0]
        assertEquals("a", term.textAt(0, 0))
        assertEquals(2, line.width[1].toInt())
        assertEquals(0, line.chars[2])      // continuation cell
        assertEquals("b", term.textAt(0, 3))
    }

    @Test
    fun combiningMarkJoinsThePreviousCell() {
        val (term, _) = emulator()
        // 'ก' U+0E01 followed by the combining vowel 'ั' U+0E31.
        term.send("\u0E01\u0E31")
        val line = term.buffer.lines[0]
        assertEquals(0x0E01, line.chars[0])
        assertEquals("\u0E31", line.combiningAt(0)?.toString())
        // The mark must not have moved the cursor.
        assertEquals(1, term.buffer.cursorCol)
    }

    @Test
    fun scrollingToTheBottomKeepsHistory() {
        val (term, _) = emulator(rows = 3)
        term.send("a\r\nb\r\nc\r\nd")
        assertEquals(1, term.buffer.scrollback.size)
        val oldest = term.buffer.scrollback.first()
        assertEquals('a'.code, oldest.chars[0])
    }

    @Test
    fun resizingWidensScrollbackRowsToo() {
        val (term, _) = emulator(cols = 10, rows = 3)
        term.send("a\r\nb\r\nc\r\nd")
        val oldest = term.buffer.scrollback.first()
        assertEquals(10, oldest.columns)
        term.resize(30, 3)
        // History is painted now, so it has to follow the live grid's width.
        assertEquals(30, oldest.columns)
        assertEquals('a'.code, oldest.chars[0])
    }

    @Test
    fun alternateScreenHidesThenRestoresTheNormalScreen() {
        val (term, _) = emulator()
        term.send("normal")
        val normalScreen = term.buffer
        term.send("\u001b[?1049h")
        assertTrue(term.buffer !== normalScreen) // switched to the alt screen
        assertEquals(" ", term.textAt(0, 0))     // which starts blank
        term.send("alt")
        assertEquals("a", term.textAt(0, 0))
        term.send("\u001b[?1049l")
        assertTrue(term.buffer === normalScreen) // normal screen is back
        assertEquals("n", term.textAt(0, 0))
    }

    @Test
    fun oscSetsTheTitle() {
        val (term, out) = emulator()
        term.send("\u001b]0;my shell\u0007")
        assertEquals("my shell", out.title)
        assertEquals("my shell", term.title)
    }

    @Test
    fun deviceStatusReportAnswersTheCursorPosition() {
        val (term, out) = emulator()
        term.send("\u001b[2;3H")
        term.send("\u001b[6n")
        assertEquals("\u001b[2;3R", out.responses.toString())
    }

    @Test
    fun resizeChangesGeometry() {
        val (term, _) = emulator(10, 3)
        term.resize(30, 8)
        assertEquals(30, term.columns)
        assertEquals(8, term.rows)
        assertEquals(8, term.buffer.lines.size)
        // The buffer's own geometry has to follow: drawing and the CSI
        // operations read buffer.columns/rows, and a stale pair after a
        // keyboard resize was why the page stopped at the old height.
        assertEquals(30, term.buffer.columns)
        assertEquals(8, term.buffer.rows)
        // A full-screen clear must reach the rows that were just added.
        term.send("top\r\n\u001b[H\u001b[2J")
        assertEquals(" ", term.textAt(7, 0))
        assertEquals(30, term.buffer.lines[7].columns)
    }

    @Test
    fun mouseReportFollowsTheMode() {
        val (term, _) = emulator()
        assertEquals(null, term.mouseReport(0, 3, 4, release = false))
        term.send("\u001b[?1000h\u001b[?1006h")
        assertEquals(
            "\u001b[<0;4;5M",
            term.mouseReport(0, 3, 4, release = false)!!.toString(Charsets.US_ASCII),
        )
    }

    @Test
    fun wheelReportsGoToTheProgram() {
        val (term, _) = emulator()
        term.send("\u001b[?1000h\u001b[?1006h")
        assertEquals(
            "\u001b[<64;4;5M",
            term.mouseReport(64, 3, 4, release = false)!!.toString(Charsets.US_ASCII),
        )
        assertEquals(
            "\u001b[<65;4;5M",
            term.mouseReport(65, 3, 4, release = false)!!.toString(Charsets.US_ASCII),
        )
    }

    @Test
    fun alternateScreenFlagTracksTheSwitch() {
        val (term, _) = emulator()
        assertFalse(term.alternateScreen)
        term.send("\u001b[?1049h")
        assertTrue(term.alternateScreen)
        term.send("\u001b[?1049l")
        assertFalse(term.alternateScreen)
    }

    @Test
    fun fullScreenIsDetectedFromAbsoluteCursorMoves() {
        val (term, _) = emulator()
        // A prompt may park the cursor once; three moves down the screen is a
        // program drawing a UI.
        term.send("\u001b[1;1H")
        assertFalse(term.fullScreenDetected)
        term.send("\u001b[5;1H\u001b[6;1H\u001b[7;1H")
        assertTrue(term.fullScreenDetected)
    }

    @Test
    fun alternateScreenCountsAsFullScreen() {
        val (term, _) = emulator()
        term.send("\u001b[?1049h")
        assertTrue(term.fullScreenDetected)
    }

    @Test
    fun modesSurviveAReattach() {
        // What a fresh emulator gets when the engine replays the tail of a
        // session that started long ago.
        val saved = TerminalModes(
            mouseTracking = 4,
            mouseSgr = true,
            alternate = true,
            applicationCursorKeys = true,
            bracketedPaste = true,
            cursorVisible = false,
            fullScreen = true,
        )
        val (term, _) = emulator()
        term.applyModes(saved)
        assertTrue(term.mouseReporting())
        assertTrue(term.alternateScreen)
        assertTrue(term.fullScreenDetected)
        assertFalse(term.cursorVisible)
        assertEquals(saved, term.snapshotModes())
        // And a wheel is still a wheel.
        assertEquals(
            "\u001b[<65;4;5M",
            term.mouseReport(65, 3, 4, release = false)!!.toString(Charsets.US_ASCII),
        )
    }

    @Test
    fun wheelCanBeEncodedWithoutTheMouseMode() {
        val (term, _) = emulator()
        // No ?1000h was seen (the session was attached mid-stream), but a
        // full-screen program speaks SGR and understands this.
        assertEquals(
            "\u001b[<64;1;1M",
            term.wheelReport(64, 0, 0).toString(Charsets.US_ASCII),
        )
        assertEquals(
            "\u001b[<0;2;3m",
            term.sgrReport(0, 1, 2, release = true).toString(Charsets.US_ASCII),
        )
    }

    @Test
    fun deviceAttributeQueriesGetTheReportTheyAskedFor() {
        val (term, out) = emulator()

        // DA1 (CSI c) asks what kind of terminal this is.
        term.send("\u001b[c")
        assertEquals("\u001b[?1;2c", out.responses.toString())

        // DA2 (CSI > c) asks a different question and needs a secondary DA
        // report. Sending the DA1 answer makes tmux treat it as input for the
        // pane, which shows up as digits typed at a prompt.
        out.responses.setLength(0)
        term.send("\u001b[>c")
        assertEquals("\u001b[>0;276;0c", out.responses.toString())
    }

    @Test
    fun cursorStatusReportsTheCursorPosition() {
        val (term, out) = emulator()
        term.send("\u001b[3;5H\u001b[6n")
        assertEquals("\u001b[3;5R", out.responses.toString())
    }

    @Test
    fun resetClearsEverything() {
        val (term, out) = emulator()
        term.send("junk\u001b[?1000h\u001b[31m")
        val redrawsBefore = out.redraws
        // The engine prefixes every attach replay with RIS, so the screen must
        // both clear and ask to be redrawn: a cleared buffer the view never
        // hears about still shows the old pixels.
        term.send("\u001bc")
        assertEquals(" ", term.textAt(0, 0))
        assertFalse(term.mouseReporting())
        assertEquals(TerminalColors.DEFAULT_FG, term.buffer.lines[0].fg[0])
        assertTrue(out.redraws > redrawsBefore)
    }
}
