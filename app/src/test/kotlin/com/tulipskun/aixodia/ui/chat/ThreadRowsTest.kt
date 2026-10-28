package com.tulipskun.aixodia.ui.chat

import com.tulipskun.aixodia.data.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The thread's window over the stored rows, while a turn is being written.
 */
class ThreadRowsTest {
    private fun row(seq: Long, role: String, text: String = "t") =
        ChatMessage(id = "$role-$seq", sessionId = "s", seq = seq, role = role, text = text, createdAt = 1000L)

    @Test fun nothingIsHiddenBetweenTurns() {
        val rows = listOf(row(1, "user"), row(2, "model"))
        assertEquals(rows, threadRows(rows, -1L))
    }

    @Test fun theTurnInFlightIsLeftToTheLiveTimeline() {
        val rows = listOf(
            row(1, "user", "older question"),
            row(2, "model", "older answer"),
            row(3, "user", "this one"),
            row(4, "model", "narration before the tool"),
            row(5, "tool_call", "bash"),
        )
        val drawn = threadRows(rows, 2L)
        assertEquals(listOf(rows[0], rows[1], rows[2]), drawn)
    }

    @Test fun theQuestionStillBeingAnsweredStaysOnScreen() {
        val rows = listOf(row(3, "user", "this one"), row(4, "tool_call", "bash"))
        assertEquals(listOf(rows[0]), threadRows(rows, 2L))
    }

    @Test fun aBaseOfZeroHidesNoHistoryButStillCutsTheNewRows() {
        // The first turn of a chat: the thread was empty when it was sent.
        val rows = listOf(row(1, "user"), row(2, "tool_call", "read"))
        assertEquals(listOf(rows[0]), threadRows(rows, 0L))
    }
}
