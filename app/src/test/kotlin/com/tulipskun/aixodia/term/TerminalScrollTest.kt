package com.tulipskun.aixodia.term

import android.view.MotionEvent
import android.view.View
import app.cash.paparazzi.Paparazzi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A finger on the grid. The page used to be pinned to the live screen, so a
 * drag did nothing unless the program turned it into a wheel; these tests move
 * a finger over a view that has history and look at where the page ended up.
 */
class TerminalScrollTest {

    @get:Rule
    val paparazzi = Paparazzi()

    private class Sink : TerminalOutput {
        val sent = StringBuilder()

        /** Wired to the view by the tests that care about a repaint, the way
         *  the panel wires it in the app. */
        var onChanged: (() -> Unit)? = null

        override fun write(bytes: ByteArray) {
            sent.append(String(bytes, Charsets.ISO_8859_1))
        }

        override fun onScreenChanged() {
            onChanged?.invoke()
        }

        override fun onTitleChanged(title: String) = Unit
    }

    private class Tty(val view: TerminalView, val emulator: TerminalEmulator, val sink: Sink) {
        val atBottom = mutableListOf<Boolean>()

        val pan: Float
            get() = TerminalView::class.java
                .getDeclaredField("scrollTopPx")
                .apply { isAccessible = true }
                .getFloat(view)

        /** The height the grid is painting a row at. */
        val cellHeight: Float
            get() = TerminalView::class.java
                .getDeclaredField("cellHeight")
                .apply { isAccessible = true }
                .getFloat(view)

        /**
         * Which row of the whole page — history first, then the live screen —
         * sits at the top of the view. Holding it across a resize is what it
         * means for the text a reader is looking at not to move.
         */
        val anchorRow: Int
            get() = emulator.buffer.scrollback.size - (pan / cellHeight).toInt()

        /** The settled layout the app reaches after the keyboard stopped moving. */
        fun resizeToView() {
            TerminalView::class.java
                .getDeclaredMethod("resizeToView")
                .apply { isAccessible = true }
                .invoke(view)
        }
    }

    /** A view laid out at a phone-sized page, over a shell that has scrolled. */
    private fun tty(lines: Int, tail: String = ""): Tty {
        val sink = Sink()
        val emulator = TerminalEmulator(80, 24, sink)
        for (i in 1..lines) emulator.write("line $i of the history\r\n".toByteArray())
        if (tail.isNotEmpty()) emulator.write(tail.toByteArray())
        val view = TerminalView(paparazzi.context)
        val tty = Tty(view, emulator, sink)
        view.inputSink = { sink.write(it) }
        view.onScrollChanged = { tty.atBottom += it }
        view.setEmulator(emulator)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1080, 1920)
        return tty
    }

    private fun TerminalView.drag(fromY: Float, toY: Float, steps: Int = 10) {
        val down = 1_000L
        dispatchTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, 540f, fromY, 0))
        for (i in 1..steps) {
            val y = fromY + (toY - fromY) * i / steps
            dispatchTouchEvent(
                MotionEvent.obtain(down, down + i * 16L, MotionEvent.ACTION_MOVE, 540f, y, 0),
            )
        }
        dispatchTouchEvent(MotionEvent.obtain(down, down + (steps + 1) * 16L, MotionEvent.ACTION_UP, 540f, toY, 0))
    }

    private fun TerminalView.tap(y: Float) {
        val at = 2_000L
        dispatchTouchEvent(MotionEvent.obtain(at, at, MotionEvent.ACTION_DOWN, 540f, y, 0))
        dispatchTouchEvent(MotionEvent.obtain(at, at + 40L, MotionEvent.ACTION_UP, 540f, y, 0))
    }

    @Test
    fun dragDownShowsHistory() {
        val tty = tty(lines = 100)
        tty.view.drag(fromY = 600f, toY = 1200f)
        assertTrue("the page should follow the finger down into history", tty.pan > 0f)
        assertFalse("the page is no longer at the prompt", tty.atBottom.last())
    }

    @Test
    fun dragUpReturnsToThePrompt() {
        val tty = tty(lines = 100)
        tty.view.drag(fromY = 200f, toY = 1200f)
        assertTrue(tty.pan > 0f)
        repeat(6) { tty.view.drag(fromY = 1400f, toY = 400f) }
        assertEquals("back at the live screen", 0f, tty.pan, 0.5f)
        assertTrue("the button disappears again", tty.atBottom.last())
    }

    @Test
    fun thePageStopsAtTheOldestRow() {
        val tty = tty(lines = 100)
        repeat(30) { tty.view.drag(fromY = 100f, toY = 1800f) }
        val max = tty.pan
        tty.view.drag(fromY = 100f, toY = 1800f)
        assertEquals("dragging past the top changes nothing", max, tty.pan, 0.5f)
    }

    @Test
    fun typingBringsThePageBack() {
        val tty = tty(lines = 100)
        tty.view.drag(fromY = 600f, toY = 1200f)
        assertTrue(tty.pan > 0f)
        tty.view.sendTyped("l")
        assertEquals(0f, tty.pan, 0.5f)
    }

    /**
     * The keyboard is a resize, and a resize used to be answered by throwing the
     * page back onto the live screen: the history a reader had pulled up slid
     * off the top of the view every time a bar appeared. The rows already on
     * screen have to keep their place instead.
     */
    @Test
    fun readingHistoryHoldsItsPlaceThroughAResize() {
        val tty = tty(lines = 100)
        tty.sink.onChanged = { tty.view.onContentChanged() }
        tty.view.drag(fromY = 900f, toY = 1500f)
        val before = tty.anchorRow
        assertTrue("the page is reading history", tty.pan > 0f)

        tty.view.layout(0, 0, 1080, 1400)
        tty.resizeToView()

        assertEquals("the row at the top of the page never moved", before, tty.anchorRow)
        assertTrue("and the page is still in the history", tty.pan > 0f)
    }

    @Test
    fun aScreenThatGrowsBackHoldsThePageToo() {
        val tty = tty(lines = 100)
        tty.sink.onChanged = { tty.view.onContentChanged() }
        tty.view.drag(fromY = 900f, toY = 1500f)
        tty.view.layout(0, 0, 1080, 1400)
        tty.resizeToView()
        val before = tty.anchorRow

        tty.view.layout(0, 0, 1080, 1920)
        tty.resizeToView()

        assertEquals(before, tty.anchorRow)
    }

    /**
     * Holding the page is about a reader who pulled it up. Someone looking at
     * the prompt has no page to hold: a shorter screen simply takes its top rows
     * into the history, the way more output would.
     */
    @Test
    fun aReaderOnThePromptStaysOnThePrompt() {
        val tty = tty(lines = 100)
        tty.sink.onChanged = { tty.view.onContentChanged() }
        assertEquals("the page opens at the live screen", 0f, tty.pan, 0.5f)

        tty.view.layout(0, 0, 1080, 1400)
        tty.resizeToView()

        assertEquals("a resize does not pull the history down under it", 0f, tty.pan, 0.5f)
        assertFalse("nor does it report the page leaving the prompt", tty.atBottom.any { !it })
    }

    /**
     * A flick up or down is a scroll, not a request to type; only a tap may
     * bring the IME up. The fast flick that ends inside the tap window used to
     * open the keyboard in the middle of scrolling.
     */
    @Test
    fun onlyATapAsksForTheKeyboard() {
        val tty = tty(lines = 100)
        var interactions = 0
        tty.view.onUserInteraction = { interactions++ }
        tty.view.drag(fromY = 600f, toY = 1200f)
        assertEquals("a drag is not a tap", 0, interactions)
        tty.view.tap(y = 900f)
        assertEquals("a tap still counts", 1, interactions)
    }

    @Test
    fun aProgramThatAskedForTheMouseGetsTheWheel() {
        val tty = tty(lines = 100, tail = "\u001b[?1000h")
        assertTrue(tty.emulator.mouseReporting())
        tty.view.drag(fromY = 1200f, toY = 600f)
        assertEquals("the page stays where the program put it", 0f, tty.pan, 0.5f)
        assertTrue("the drag arrived as a wheel", tty.sink.sent.isNotEmpty())
    }

    /**
     * The guess that a cursor-parking program is full-screen is set by tuis that
     * paint inline and is never cleared after they exit, so it must not decide
     * whether the page may scroll — that is what kept a plain prompt frozen.
     */
    @Test
    fun theFullScreenGuessDoesNotFreezeAPlainPrompt() {
        val tty = tty(lines = 100, tail = "\u001b[5;10H\u001b[10;20H\u001b[15;5H")
        assertTrue(tty.emulator.fullScreenDetected)
        assertFalse(tty.emulator.mouseReporting())
        assertFalse(tty.emulator.alternateScreen)
        tty.view.drag(fromY = 600f, toY = 1200f)
        assertTrue("a plain prompt still scrolls", tty.pan > 0f)
    }

    @Test
    fun aTapIsStillAClickForAGuessedMouseProgram() {
        val tty = tty(lines = 100, tail = "\u001b[5;10H\u001b[10;20H\u001b[15;5H")
        tty.view.tap(y = 900f)
        assertTrue("a guessed program still sees the click", tty.sink.sent.contains("\u001b[<"))
    }
}
