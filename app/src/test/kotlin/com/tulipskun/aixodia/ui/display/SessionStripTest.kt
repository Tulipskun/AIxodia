package com.tulipskun.aixodia.ui.display

import com.tulipskun.aixodia.data.model.ChatMessage
import com.tulipskun.aixodia.data.model.normalizedRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The strip adds a thread up, so what it adds is data and can be checked without
 * a screen.
 *
 * The role is the part worth pinning down. This app writes "model" for a reply it
 * makes itself and the daemon's D1 rows say "assistant", the name the provider
 * APIs use — and a total that only knew the first spelling added every real
 * thread up to nothing and drew no strip at all.
 */
class SessionStripTest {

    private fun msg(
        seq: Long,
        role: String,
        tokensIn: Int = 0,
        tokensOut: Int = 0,
        cacheRead: Int = 0,
        inputIncludesCache: Boolean = false,
        durationMs: Long = 0,
    ) = ChatMessage(
        id = "m$seq",
        sessionId = "s1",
        seq = seq,
        role = role,
        text = "",
        createdAt = 0,
        tokensIn = tokensIn,
        tokensOut = tokensOut,
        cacheRead = cacheRead,
        inputIncludesCache = inputIncludesCache,
        durationMs = durationMs,
    )

    @Test
    fun `a turn the daemon calls assistant counts like any other answer`() {
        val totals = sessionTotals(
            listOf(
                msg(1, "user", tokensIn = 30),
                msg(2, "assistant", tokensIn = 12_845, tokensOut = 826, durationMs = 62_669),
                msg(3, "tool_call"),
                msg(4, "tool_result", tokensIn = 500),
                msg(5, "assistant", tokensIn = 6_103, tokensOut = 446, durationMs = 7_381),
            ),
        )
        assertEquals(18_948, totals.promptTokens)
        assertEquals(1_272, totals.outTokens)
        assertEquals(7_381L, totals.lastTurnMs)
        assertEquals(70_050L, totals.totalMs)
        assertFalse(totals.isEmpty)
    }

    @Test
    fun `a question and a tool result carry no counts of their own`() {
        val totals = sessionTotals(
            listOf(
                msg(1, "user", tokensIn = 900, tokensOut = 900, durationMs = 5_000),
                msg(2, "tool_result", tokensIn = 900, tokensOut = 900, durationMs = 5_000),
            ),
        )
        assertTrue(totals.isEmpty)
    }

    @Test
    fun `the cache share is the part of the prompt the cache served`() {
        val totals = sessionTotals(
            listOf(msg(1, "model", tokensIn = 25_000, cacheRead = 19_500, tokensOut = 10, inputIncludesCache = true)),
        )
        assertEquals(25_000, totals.promptTokens)
        assertEquals(78, totals.cachePercent)
    }

    @Test
    fun `the last turn is the newest one, not the longest`() {
        val totals = sessionTotals(
            listOf(
                msg(1, "model", tokensOut = 1, durationMs = 9_000),
                msg(2, "model", tokensOut = 1, durationMs = 2_000),
            ),
        )
        assertEquals(2_000L, totals.lastTurnMs)
        assertEquals(11_000L, totals.totalMs)
    }

    @Test
    fun `a turn with no reported duration stays out of the times`() {
        // Counting a missing duration as instant would drag the session average
        // and make a slow turn read as a fast one.
        val totals = sessionTotals(
            listOf(
                msg(1, "model", tokensOut = 5, durationMs = 0),
                msg(2, "model", tokensOut = 5, durationMs = 2_000),
            ),
        )
        assertEquals(2_000L, totals.totalMs)
        assertEquals(2_000L, totals.lastTurnMs)
    }

    @Test
    fun `the daemon's role is folded into this app's own word`() {
        assertEquals("model", normalizedRole("assistant"))
        assertEquals("model", normalizedRole(""))
        assertEquals("model", normalizedRole("model"))
        assertEquals("user", normalizedRole("user"))
        assertEquals("tool_call", normalizedRole("tool_call"))
        assertEquals("tool_result", normalizedRole("tool_result"))
    }
}
