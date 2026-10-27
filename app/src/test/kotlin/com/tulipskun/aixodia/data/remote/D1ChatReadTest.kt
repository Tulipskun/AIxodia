package com.tulipskun.aixodia.data.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fallback chat reads: when the tunnel cannot be dialled the phone reads
 * `chat_sessions`/`chat_turns` straight out of D1, and a wrong column reading
 * there shows wrong content instead of failing, so the mapping is pinned here.
 */
class D1ChatReadTest {

    @Test fun aSessionRowCarriesUsageFromTheSqlColumns() {
        val row = d1SessionRow(
            JSONObject()
                .put("id", "abc123")
                .put("title", "notebook work")
                .put("provider", "zai")
                .put("model", "glm-5")
                .put("updated_at", 1785123456)
                .put("usage_prompt_tokens", 120)
                .put("usage_cache_read", 80)
                .put("usage_fresh_input", 40)
                .put("usage_output_tokens", 950)
                .put("usage_total_ms", 61000)
                .put("usage_last_turn_ms", 12500),
        )
        assertEquals("abc123", row.id)
        assertEquals("notebook work", row.title)
        assertEquals("zai", row.provider)
        assertEquals("glm-5", row.model)
        assertEquals(1785123456L, row.updatedAt)
        val usage = row.usage!!
        assertEquals(120, usage.promptTokens)
        assertEquals(80, usage.cacheRead)
        assertEquals(40, usage.freshInput)
        assertEquals(950, usage.outTokens)
        assertEquals(61000L, usage.totalMs)
        assertEquals(12500L, usage.lastTurnMs)
    }

    @Test fun aTurnRowReadsTheFooterColumnsAndTheBooleanFlag() {
        val row = d1TurnRow(
            JSONObject()
                .put("id", 17)
                .put("session_id", "abc123")
                .put("seq", 3)
                .put("role", "assistant")
                .put("agent", "main")
                .put("job_id", "j-9")
                .put("text", "done — pushed")
                .put("created_at", 1785123499)
                .put("model", "glm-5")
                .put("input_tokens", 512)
                .put("output_tokens", 96)
                .put("cache_read_tokens", 480)
                .put("cache_write_tokens", 32)
                .put("reasoning_tokens", 8)
                .put("input_includes_cache", 1)
                .put("duration_ms", 2400)
                .put("generation_ms", 1900)
                .put("reasoning_content", "checking first")
                .put("block", "{\"id\":\"answer\"}"),
        )
        assertEquals(17L, row.messageId)
        assertEquals(3L, row.seq)
        assertEquals("assistant", row.role)
        assertEquals("main", row.agent)
        assertEquals("j-9", row.jobId)
        assertEquals("done — pushed", row.text)
        assertEquals(1785123499L, row.createdAt)
        assertEquals("glm-5", row.model)
        assertEquals(512, row.inputTokens)
        assertEquals(96, row.outputTokens)
        assertEquals(480, row.cacheRead)
        assertEquals(32, row.cacheWrite)
        assertEquals(8, row.reasoningTokens)
        assertTrue(row.inputIncludesCache)
        assertEquals(2400L, row.durationMs)
        assertEquals(1900L, row.generationMs)
        assertEquals("checking first", row.reasoningContent)
        assertEquals("{\"id\":\"answer\"}", row.block)
    }

    @Test fun textBackedNumbersAndSqlNullsReadAsTheirDefaults() {
        // A database that went through a text round trip hands digits back as
        // strings, and SQL NULL must not read as optString's literal "null".
        val row = d1TurnRow(
            JSONObject()
                .put("seq", "5")
                .put("id", "41")
                .put("role", "user")
                .put("created_at", "1785123500")
                .put("input_tokens", "37")
                .put("input_includes_cache", 0)
                .put("generation_ms", "1200")
                .put("block", JSONObject.NULL)
                .put("reasoning_content", JSONObject.NULL)
        )
        assertEquals(5L, row.seq)
        assertEquals(41L, row.messageId)
        assertEquals(1785123500L, row.createdAt)
        assertEquals(37, row.inputTokens)
        assertEquals(1200L, row.generationMs)
        assertFalse(row.inputIncludesCache)
        assertEquals("", row.block)
        assertEquals("", row.reasoningContent)
    }

    @Test fun missingColumnsReadAsZeroRatherThanThrowing() {
        // A database the engine has not run ensure_turns on yet may drop an
        // older column; the row still maps, with blanks.
        val row = d1TurnRow(JSONObject().put("seq", 2).put("role", "user").put("text", "hi"))
        assertEquals(0L, row.messageId)
        assertEquals(0, row.inputTokens)
        assertFalse(row.inputIncludesCache)
        assertEquals("", row.block)
    }

    @Test fun aMappedD1PageSlicesTheSameWayTheDaemonPageDoes() {
        // The tunnel read and the D1 read must produce interchangeable lists:
        // pageTurns is applied to both, so the merge logic cannot tell which
        // one answered.
        val rows = listOf(
            JSONObject().put("seq", 6).put("role", "assistant").put("text", "six"),
            JSONObject().put("seq", 5).put("role", "user").put("text", "five"),
            JSONObject().put("seq", 4).put("role", "assistant").put("text", "four"),
        ).map(::d1TurnRow).sortedBy { it.seq }

        val page = pageTurns(rows, beforeSeq = 5, limit = 2)
        assertEquals(listOf(4L), page.map { it.seq })
        val newest = pageTurns(rows, beforeSeq = 0, limit = 2)
        assertEquals(listOf(5L, 6L), newest.map { it.seq })
    }
}
