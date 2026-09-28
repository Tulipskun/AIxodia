package com.tulipskun.aixodia.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The live answer is built from deltas and then from the daemon's own copy.
 * These are the exact shapes that used to put the same sentence on screen
 * twice.
 */
class LiveAnswerMergeTest {

    private val streamed = "I have two available tools:\n\n" +
        "1. **read** - Read UTF-8 text files inside the workspace\n" +
        "2. **bash** - Run shell commands in a persistent session"

    // What a provider reports at the end: the same answer without the Markdown.
    private val plain = "I have two available tools:\n\n" +
        "1. read - Read UTF-8 text files inside the workspace\n" +
        "2. bash - Run shell commands in a persistent session"

    @Test
    fun `deltas build the answer in order`() {
        var text = ""
        for (piece in listOf("I have two ", "available tools:\n\n", "1. **read** - Read ", "done")) {
            text = mergeLiveAnswer(text, piece, replace = false)
        }
        assertEquals("I have two available tools:\n\n1. **read** - Read done", text)
    }

    @Test
    fun `the authoritative message replaces the buffer`() {
        val short = "wrong answer"
        assertEquals(streamed, mergeLiveAnswer(short, streamed, replace = true))
    }

    @Test
    fun `the plainer authoritative copy does not overwrite the streamed markdown`() {
        assertEquals(streamed, mergeLiveAnswer(streamed, plain, replace = true))
    }

    @Test
    fun `a replayed delta is ignored`() {
        val once = mergeLiveAnswer("", "**read** - Read UTF-8 text files", replace = false)
        val twice = mergeLiveAnswer(once, "**read** - Read UTF-8 text files", replace = false)
        assertEquals(once, twice)
    }

    @Test
    fun `the whole answer replayed as deltas stays one copy`() {
        var text = ""
        for (piece in listOf("I have two available tools:\n\n1. **read** - Read UTF-8 text files inside the workspace", "I have two available tools:\n\n1. **read** - Read UTF-8 text files inside the workspace")) {
            text = mergeLiveAnswer(text, piece, replace = false)
        }
        assertEquals(
            "I have two available tools:\n\n1. **read** - Read UTF-8 text files inside the workspace",
            text,
        )
    }

    @Test
    fun `an empty chunk changes nothing`() {
        assertEquals("answer", mergeLiveAnswer("answer", "", replace = false))
        assertEquals("answer", mergeLiveAnswer("answer", "", replace = true))
    }

    @Test
    fun `a short overlapping chunk is appended so words are not lost`() {
        // "e" alone is a real delta; the dedupe rule must not eat it.
        assertEquals("the", mergeLiveAnswer("th", "e", replace = false))
    }
}
