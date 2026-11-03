package com.tulipskun.aixodia.ui.display

import com.tulipskun.aixodia.data.model.ChatMessage
import com.tulipskun.aixodia.ui.chat.TurnStats
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageTokenCountsTest {
    private fun message(includesCache: Boolean, input: Int = 50) = ChatMessage(
        id = "s:1", sessionId = "s", seq = 1, role = "model", text = "answer",
        createdAt = 0, tokensIn = input, cacheRead = 10, cacheWrite = 5,
        inputIncludesCache = includesCache,
    )

    @Test
    fun `separately reported input and cache writes are both fresh`() {
        val counts = message(false).toTokenCounts()
        assertEquals(10, counts.cacheRead)
        assertEquals(55, counts.cacheWrite)
    }

    @Test
    fun `inclusive input subtracts cache reads without adding cache writes again`() {
        assertEquals(40, message(true).toTokenCounts().cacheWrite)
    }

    @Test
    fun `an inconsistent inclusive count cannot become negative fresh input`() {
        assertEquals(0, message(true, input = 5).toTokenCounts().cacheWrite)
    }

    @Test
    fun `live statistics follow the same separate-cache convention as stored messages`() {
        assertEquals(55, TurnStats(inputTokens = 50, cacheRead = 10, cacheWrite = 5).freshInputTokens())
    }
}
