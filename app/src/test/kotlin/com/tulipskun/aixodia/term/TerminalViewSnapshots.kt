package com.tulipskun.aixodia.term

import app.cash.paparazzi.Paparazzi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Renders [TerminalView] on the JVM with Paparazzi, so the grid, the colours
 * and the glyph metrics can be checked in seconds instead of on a device.
 */
class TerminalViewSnapshots {

    @get:Rule
    val paparazzi = Paparazzi()

    private class Sink : TerminalOutput {
        override fun write(bytes: ByteArray) = Unit
        override fun onScreenChanged() = Unit
        override fun onTitleChanged(title: String) = Unit
    }

    private fun render(script: TerminalEmulator.() -> Unit) {
        val emulator = TerminalEmulator(80, 24, Sink())
        emulator.script()
        val view = TerminalView(paparazzi.context)
        view.setEmulator(emulator)
        paparazzi.snapshot(view)
    }

    @Test
    fun shellWithColours() = render {
        write("\u001b[1;32muser@host\u001b[0m:\u001b[1;34m~/proj\u001b[0m$ ls --color=always\r\n".toByteArray())
        write("\u001b[1;34msrc\u001b[0m  \u001b[1;34mdocs\u001b[0m  README.md  \u001b[1;32mbuild.sh\u001b[0m\r\n".toByteArray())
        write("user@host:~/proj$ \u001b[7m \u001b[0m".toByteArray())
    }

    @Test
    fun thaiAndWideGlyphs() = render {
        write("สวัสดีครับ ทดสอบภาษาไทย\r\n".toByteArray())
        write("wide: \u4e2d\u6587\u5b57 ok\r\n".toByteArray())
        write("bold \u001b[1mตัวหนา\u001b[0m  underline \u001b[4mเส้นใต้\u001b[0m\r\n".toByteArray())
        write("\u001b[41m red bg \u001b[0m \u001b[38;2;255;180;0mtruecolour\u001b[0m\r\n".toByteArray())
    }

    @Test
    fun fullScreenBoxDrawing() = render {
        write("\u001b[2J\u001b[H".toByteArray())
        write("\u250c\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2510\r\n".toByteArray())
        write("\u2502  opencode   \u2502\r\n".toByteArray())
        write("\u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2518\r\n".toByteArray())
    }

    /**
     * A phone-sized page, laid out the way the panel lays the grid out, so a
     * snapshot shows what a reader sees — including where the page is pulled to.
     */
    private class Page(val view: TerminalView, val emulator: TerminalEmulator) {
        /** Bytes off the wire, straight into the screen. */
        fun feed(text: String) = emulator.write(text.toByteArray())

        /** What the row at the top of the view says — the line a reader is on. */
        val topRowText: String
            get() {
                val pan = TerminalView::class.java
                    .getDeclaredField("scrollTopPx").apply { isAccessible = true }.getFloat(view)
                val cell = TerminalView::class.java
                    .getDeclaredField("cellHeight").apply { isAccessible = true }.getFloat(view)
                val history = emulator.buffer.scrollback
                val page = history + emulator.buffer.lines
                if (page.isEmpty()) return ""
                return page[(history.size - (pan / cell).toInt()).coerceIn(0, page.size - 1)].text()
            }

        /** Pulls the page down by [pixels], which is how a finger reaches history. */
        fun dragDown(pixels: Float) {
            val down = 1_000L
            view.dispatchTouchEvent(
                android.view.MotionEvent.obtain(down, down, android.view.MotionEvent.ACTION_DOWN, 540f, 1500f, 0),
            )
            for (i in 1..10) {
                view.dispatchTouchEvent(
                    android.view.MotionEvent.obtain(
                        down, down + i * 16L, android.view.MotionEvent.ACTION_MOVE,
                        540f, 1500f + pixels * i / 10, 0,
                    ),
                )
            }
            view.dispatchTouchEvent(
                android.view.MotionEvent.obtain(
                    down, down + 176L, android.view.MotionEvent.ACTION_UP, 540f, 1500f + pixels, 0,
                ),
            )
        }
    }

    private fun phonePage(script: Page.() -> Unit): Page {
        // The view is made first so the sink can hand it the news, the way the
        // panel does: output tells the view, and the view decides where the
        // page sits.
        val view = TerminalView(paparazzi.context)
        val sink = object : TerminalOutput {
            override fun write(bytes: ByteArray) = Unit
            override fun onScreenChanged() = view.onContentChanged()
            override fun onTitleChanged(title: String) = Unit
        }
        val emulator = TerminalEmulator(80, 24, sink)
        val page = Page(view, emulator)
        // What the grid sends comes back as output, the way a pane answers: a
        // render that dropped the bytes would show a composition that never
        // reached the line and call that Thai. A DEL is what the line discipline
        // erases, and it erases it the way the reader's own backspace key does.
        page.view.inputSink = { bytes ->
            val echo = StringBuilder()
            for (ch in String(bytes, Charsets.UTF_8)) {
                echo.append(if (ch == '\u007f') "\b \b" else ch)
            }
            emulator.write(echo.toString().toByteArray(Charsets.UTF_8))
        }
        page.view.setEmulator(emulator)
        page.view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY),
        )
        page.view.layout(0, 0, 1080, 1920)
        page.script()
        return page
    }

    /**
     * What a pane looks like when its history has been handed to the client and
     * the reader drags it down over the prompt: the lines that scrolled off while
     * the shell was busy, which used to be reachable only by paging a multiplexer
     * that repainted on its own clock.
     */
    @Test
    fun pulledUpOverThePaneHistory() {
        val page = phonePage {
            for (i in 1..200) emulator.write("build step ${i}\r\n".toByteArray())
            emulator.write("\r\nuser@host:~/proj$ make deploy\r\n".toByteArray())
            dragDown(600f)
        }
        paparazzi.snapshot(page.view)
    }

    /**
     * The same page, either side of the engine handing the pane over again: a
     * resize makes the engine answer with a reset and the whole screen, which
     * used to land the reader back on the live prompt with their lines gone
     * above them. These two are the picture the complaint was about, and they
     * have to show the same text.
     */
    @Test
    fun aRebuildUnderThePulledPageKeepsItsLines() {
        val page = phonePage {
            for (i in 1..200) emulator.write("build step ${i}\r\n".toByteArray())
            emulator.write("\r\nuser@host:~/proj$ make deploy\r\n".toByteArray())
            dragDown(600f)
        }
        val read = page.topRowText
        paparazzi.snapshot(page.view, name = "before")
        // RIS is what the replay opens with, then the pane arrives line by line.
        page.feed("\u001bc")
        for (i in 1..200) page.feed("build step $i\r\n")
        page.feed("\r\nuser@host:~/proj$ make deploy\r\n")
        assertEquals("the pane handed over again leaves the reader on their line", read, page.topRowText)
        paparazzi.snapshot(page.view, name = "after")
    }

    /**
     * Thai assembled by the keyboard, before any of it is committed. A greeting
     * arrives one piece at a time and a tone mark rewrites the word it belongs
     * to, so a reader who types Thai sees the syllable they are building rather
     * than a cursor sitting on nothing until they press space.
     */
    @Test
    fun thaiOnTheWayToTheLine() {
        val page = phonePage {
            emulator.write("user@host:~/proj$ ".toByteArray())
            view.updateComposing("สวัสดี")
            view.updateComposing("สวัสดีค")
            view.updateComposing("สวัสดีครั")
            view.updateComposing("สวัสดีครับ")
            // The keyboard settles on exactly what it had already shown, so the
            // line is left alone rather than typed twice.
            view.commitComposed("สวัสดีครับ")
            // A new word, and the mark that arrives takes the last character
            // back before putting the new one there.
            view.updateComposing("ไก")
            view.updateComposing("ไก่")
        }
        paparazzi.snapshot(page.view)
    }
    /**
     * A reattach replays ESC c and the prompt. If the reset did not reach the
     * screen the reader would see the earlier prompt above the new one.
     */
    @Test
    fun reattachedSessionShowsOnePrompt() = render {
        write("\u001bc\u001b[?2004hroot@instabox-main:~# ".toByteArray())
        // The link drops and dials again: the engine replays the same prefix.
        write("\u001bc\u001b[?2004hroot@instabox-main:~# ".toByteArray())
    }

}
