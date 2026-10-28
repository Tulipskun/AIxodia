package com.tulipskun.aixodia.ui.chat

import com.tulipskun.aixodia.data.model.ChatMessage
import com.tulipskun.aixodia.data.repo.MessageWindow
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

    @Test fun anotherChatsRowsNeverPaintUnderThisHeader() {
        // A switch changes the chat before the new chat's Room read has
        // emitted, so the flow still holds the chat being left. Its rows are
        // not this chat's history.
        val rows = listOf(row(1, "user"), row(2, "model"))
        assertEquals(emptyList<ChatMessage>(), threadRows(rows, -1L, sid = "other"))
    }

    @Test fun theSwitchFilterKeepsThisChatWhole() {
        val rows = listOf(row(1, "user"), row(2, "model"), row(3, "model"))
        assertEquals(rows, threadRows(rows, -1L, sid = "s"))
        // It composes with the in-flight turn rule rather than replacing it.
        assertEquals(listOf(rows[0]), threadRows(rows, 0L, sid = "s"))
    }

    @Test fun theWindowFloorIsTheOldestCachedRow() {
        val rows = listOf(row(7, "user"), row(8, "model"))
        assertEquals(7L, windowFloorFor(MessageWindow(rows, hasOlder = true)))
    }

    @Test fun anEmptyOrUnreadCacheKeepsTheWindowOpen() {
        // A chat with no rows yet must still map the first send's row, so the
        // window opens below every seq instead of closing.
        assertEquals(Long.MIN_VALUE, windowFloorFor(MessageWindow(emptyList(), hasOlder = false)))
        assertEquals(Long.MIN_VALUE, windowFloorFor(null))
    }
}
