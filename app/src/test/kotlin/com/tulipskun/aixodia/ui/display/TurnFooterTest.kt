package com.tulipskun.aixodia.ui.display

import com.tulipskun.aixodia.data.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnFooterTest {
    private fun row(seq: Long, role: String, text: String = "text") = ChatMessage(
        id = "s:$seq",
        sessionId = "s",
        seq = seq,
        role = role,
        text = text,
        createdAt = 1_000L + seq,
    )

    @Test
    fun `tool rounds gain one footer only when the closing answer arrives`() {
        val rounds = listOf(
            row(1, "user", "Check Go and run tests"),
            row(2, "assistant", "Checking Go").copy(thinkingMs = 2_300L),
            row(3, "tool_call"),
            row(4, "tool_result").copy(durationMs = 900L),
            row(5, "model", "Running tests"),
            row(6, "tool_call"),
            row(7, "tool_result").copy(durationMs = 31_500L),
        )
        // Covers partial mirrors as well as a full history reload.
        for (size in 1..rounds.size) {
            assertTrue(turnFooterIds(rounds.take(size)).isEmpty())
        }
        val final = row(8, "assistant", "Tests passed").copy(
            model = "big-pickle",
            tokensIn = 1_200,
            tokensOut = 300,
            durationMs = 42_000L,
        )
        assertEquals(setOf(final.id), turnFooterIds(rounds + final))
    }

    @Test
    fun `each completed user turn keeps its footer while the next runs`() {
        val first = row(2, "assistant").copy(model = "big-pickle", durationMs = 2_000L)
        val second = row(7, "model").copy(model = "big-pickle", durationMs = 8_000L)
        val history = listOf(
            row(1, "user"), first,
            row(3, "user"), row(4, "assistant"), row(5, "tool_call"), row(6, "tool_result"), second,
            row(8, "user").copy(pending = true), row(9, "assistant"), row(10, "tool_call"),
        )
        assertEquals(setOf(first.id, second.id), turnFooterIds(history))
    }

    @Test
    fun `a model on an old narration row does not add another footer`() {
        val final = row(6, "model").copy(model = "big-pickle")
        val history = listOf(
            row(1, "user"),
            row(2, "assistant").copy(model = "big-pickle", tokensOut = 20),
            row(3, "tool_result").copy(durationMs = 200L),
            row(4, "assistant").copy(model = "big-pickle"),
            row(5, "assistant"),
            final,
        )
        assertTrue(turnFooterIds(history.take(3)).isEmpty())
        assertEquals(setOf(final.id), turnFooterIds(history))
    }

    @Test
    fun `worker and system messages do not replace the main turn summary`() {
        val final = row(3, "model").copy(agent = "main", model = "big-pickle")
        val history = listOf(
            row(1, "user"),
            row(2, "model").copy(agent = "sub", model = "worker-model"),
            final,
            row(4, "tool_result").copy(agent = "sub", durationMs = 900L),
            row(5, "model").copy(agent = "worker", model = "worker-model"),
            row(6, "system").copy(model = "big-pickle"),
        )
        assertEquals(setOf(final.id), turnFooterIds(history))
    }

    @Test
    fun `a clock or local thinking time is not evidence that the loop exited`() {
        val narration = row(2, "assistant").copy(thinkingMs = 5_000L, reasoningContent = "Planning")
        assertTrue(turnFooterIds(listOf(row(1, "user"), narration)).isEmpty())
        assertTrue(turnFooterIds(listOf(narration.copy(model = "big-pickle", pending = true))).isEmpty())
        assertTrue(turnFooterIds(emptyList()).isEmpty())
    }
}
