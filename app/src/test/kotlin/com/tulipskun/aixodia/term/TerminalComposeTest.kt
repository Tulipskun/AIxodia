package com.tulipskun.aixodia.term

import app.cash.paparazzi.Paparazzi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * What a keyboard that assembles a word one mark at a time costs the shell.
 *
 * Thai is the case the reader asked about: the syllable ไก่ is offered as ไ,
 * then ไก, then ไก่, and a tone mark that replaces another rewrites the end of
 * it. None of that is a commit, so a terminal that only listens to commits shows
 * a page that stays blank while a Thai sentence is being built.
 */
class TerminalComposeTest {

    @get:Rule
    val paparazzi = Paparazzi()

    private class Sink : TerminalOutput {
        val sent = StringBuilder()
        override fun write(bytes: ByteArray) {
            sent.append(String(bytes, Charsets.UTF_8))
        }

        override fun onScreenChanged() = Unit
        override fun onTitleChanged(title: String) = Unit
    }

    /** A grid with somewhere for the bytes to go, laid out as the view needs. */
    private fun typing(): Pair<TerminalView, Sink> {
        val sink = Sink()
        val view = TerminalView(paparazzi.context)
        view.inputSink = { sink.write(it) }
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1080, 1920)
        return view to sink
    }

    @Test
    fun anAppendedMarkIsOnlyTheMark() {
        assertEquals(TerminalCompose.Edit(0, "ไ"), TerminalCompose.edit("", "ไ"))
        assertEquals(TerminalCompose.Edit(0, "ก"), TerminalCompose.edit("ไ", "ไก"))
        assertEquals(TerminalCompose.Edit(0, "\u0E48"), TerminalCompose.edit("ไก", "ไก่"))
    }

    @Test
    fun aReplacedMarkIsTakenBackFirst() {
        // ไม้โท becomes ไม้เอก: one character goes, one comes, the rest is shared.
        assertEquals(
            TerminalCompose.Edit(1, "\u0E48"),
            TerminalCompose.edit("กา\u0E49", "กา\u0E48"),
        )
    }

    @Test
    fun aShorteningCompositionDeletesTheRest() {
        // ไก่ loses its tone mark: one character goes, none come.
        assertEquals(TerminalCompose.Edit(1, ""), TerminalCompose.edit("ไก่", "ไก"))
        assertEquals(TerminalCompose.Edit(3, ""), TerminalCompose.edit("ไก่", ""))
    }

    @Test
    fun aCommitOfWhatIsAlreadyShownIsSilent() {
        // The IME had shown the whole word and now says it is final: the shell has
        // every byte of it already, and retyping would double the syllable.
        val idle = TerminalCompose.edit("ไก่", "ไก่")
        assertEquals(0, idle.erases)
        assertEquals("", idle.types)
    }

    @Test
    fun charactersCountAsThemselvesNotAsCells() {
        // An emoji is two UTF-16 units and one character; a tone mark is one unit
        // and one character, however wide or narrow it draws.
        assertEquals(TerminalCompose.Edit(0, "🙂"), TerminalCompose.edit("", "🙂"))
        assertEquals(TerminalCompose.Edit(1, ""), TerminalCompose.edit("🙂", ""))
    }

    @Test
    fun buildingAWordOnTheLineIsBackspacesAndLetters() {
        val (view, sink) = typing()
        view.updateComposing("\u0E44")      // ไ
        view.updateComposing("\u0E44\u0E01") // ไก
        view.updateComposing("\u0E44\u0E01\u0E48") // ไก่
        // Each step was an append, so nothing was taken back and every character
        // reached the line exactly once — which is what the reader now sees.
        assertEquals("\u0E44\u0E01\u0E48", sink.sent.toString())
    }

    @Test
    fun aCommitReplacesOnlyWhatDiffers() {
        val (view, sink) = typing()
        view.updateComposing("ไก")
        sink.sent.setLength(0)
        // The keyboard was still showing ไก and now commits ไก่: only the mark it
        // had not offered yet reaches the line.
        view.commitComposed("ไก่")
        assertEquals("\u0E48", sink.sent.toString())

        // A commit of what the composition already put there sends nothing at all;
        // typing it again would leave the syllable twice on the line.
        view.updateComposing("ไก่")
        sink.sent.setLength(0)
        view.commitComposed("ไก่")
        assertEquals("", sink.sent.toString())
    }

    @Test
    fun aMarkSwappedForAnotherTakesOneBack() {
        val (view, sink) = typing()
        view.updateComposing("กา\u0E49")
        sink.sent.setLength(0)
        view.updateComposing("กา\u0E48")
        assertEquals("\u007f\u0E48", sink.sent.toString())
    }

    @Test
    fun aKeyEndsTheCompositionAndKeepsTheWord() {
        val (view, sink) = typing()
        view.updateComposing("ไก่")
        sink.sent.setLength(0)
        // Enter submits the line. The syllable belongs to it; a newline does not
        // replace the last character the way another syllable would.
        view.commitComposed("\r")
        assertEquals("\r", sink.sent.toString())
    }
}
