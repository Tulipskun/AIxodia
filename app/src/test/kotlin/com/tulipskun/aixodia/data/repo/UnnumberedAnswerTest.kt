package com.tulipskun.aixodia.data.repo

import com.tulipskun.aixodia.data.model.AiOutput
import com.tulipskun.aixodia.data.remote.TurnRow
import org.junit.Assert.*
import org.junit.Test

/**
 * The engine answers while its rows are still on the way to D1, so the frame
 * that closes a turn carries an answer with no seq. These are the shapes that
 * must be kept locally and the shapes that must not — a phone that invents a
 * row for an answer it already has a numbered copy of draws the bubble twice.
 */
class UnnumberedAnswerTest {
    @Test fun socketClosingMessageIsKeptWithItsFooter() {
        val row = unnumberedAnswer(
            AiOutput(
                sessionId = "s", kind = "message", role = "model", seq = 0,
                text = "the answer", model = "big-pickle", inputTokens = 120,
                outputTokens = 7, cacheRead = 80, durationMs = 1900, generationMs = 1400,
                reasoningContent = "thinking",
            )
        )!!
        assertEquals("the answer", row.text)
        assertEquals(0L, row.seq)
        assertEquals("big-pickle", row.model)
        assertEquals(120, row.inputTokens)
        assertEquals(7, row.outputTokens)
        assertEquals(80, row.cacheRead)
        assertEquals(1900L, row.durationMs)
        assertEquals(1400L, row.generationMs)
        assertEquals("thinking", row.reasoningContent)
    }

    @Test fun assistantSpellingIsTheSameAnswer() {
        val row = unnumberedAnswer(AiOutput(sessionId = "s", kind = "message", role = "assistant", text = "hi"))!!
        assertEquals("hi", row.text)
    }

    @Test fun numberedAnswerFromAnEngineThatWaitedIsNotReinvented() {
        assertNull(unnumberedAnswer(AiOutput(sessionId = "s", kind = "message", text = "the answer", seq = 42)))
    }

    @Test fun streamingDeltaAndToolRowAreNotAnswers() {
        assertNull(unnumberedAnswer(AiOutput(sessionId = "s", kind = "delta", text = "partial", seq = 0)))
        assertNull(unnumberedAnswer(AiOutput(sessionId = "s", kind = "trace", text = "thinking out loud", seq = 0)))
        assertNull(unnumberedAnswer(AiOutput(sessionId = "s", kind = "message", role = "tool", text = "output", seq = 0)))
        assertNull(unnumberedAnswer(AiOutput(sessionId = "s", kind = "message", role = "user", text = "a question", seq = 0)))
        assertNull(unnumberedAnswer(AiOutput(sessionId = "s", kind = "message", text = "   ", seq = 0)))
        assertNull(unnumberedAnswer(AiOutput(sessionId = "s", kind = "done", turnsSynced = false)))
    }

    @Test fun runFinalEventSuppliesItsUnnumberedTurn() {
        val turn = TurnRow(seq = 0, role = "assistant", text = "from the run", model = "big-pickle")
        val row = unnumberedAnswer(AiOutput(sessionId = "s", kind = "message", text = "from the run", turns = listOf(turn)))!!
        assertSame(turn, row)
    }

    @Test fun runFinalEventWithAStoredTurnIsLeftToTheStoredRow() {
        val turn = TurnRow(seq = 9, role = "assistant", text = "already stored")
        assertNull(unnumberedAnswer(AiOutput(sessionId = "s", kind = "message", text = "already stored", seq = 9, turns = listOf(turn))))
    }
}
