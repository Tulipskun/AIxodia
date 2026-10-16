package com.tulipskun.aixodia.data.repo

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.tulipskun.aixodia.data.local.MessageEntity
import com.tulipskun.aixodia.data.model.AiOutput
import org.junit.Assert.*
import org.junit.Test

class CommittedTurnsTest {
    private fun row(seq: Long, role: String, text: String, clientId: String = "") =
        MessageEntity("s", seq, role, text, 1000, clientMsgId = clientId)

    @Test fun newTailKeepsTheOlderThreadAndExactFooter() {
        val old = row(1, "model", "older", "old-id").copy(thinkingMs = 250)
        val user = row(2, "user", "hi", "c1")
        val answer = row(3, "model", "ok").copy(
            tokensIn = 100, tokensOut = 5, cacheRead = 80, cacheWrite = 2,
            inputIncludesCache = true, reasoningContent = "actual reasoning",
            reasoningTokens = 2, model = "big-pickle", durationMs = 9000, generationMs = 4000,
        )
        val merged = mergeCommittedTurns(listOf(old, user), listOf(user.copy(clientMsgId = ""), answer), "c1")
        assertEquals(listOf(old, user, answer), merged)
        assertEquals(merged, mergeCommittedTurns(merged, listOf(user.copy(clientMsgId = ""), answer), "c1"))
    }

    @Test fun clientIdAdoptsUserEvenWhenSeqAndAttachmentTextChanged() {
        val old = row(1, "model", "older")
        val optimistic = row(2, "user", "photo", "c1")
        val committed = row(6, "user", "photo\n[ไฟล์แนบ]")
        val merged = mergeCommittedTurns(listOf(old, optimistic), listOf(committed), "c1")
        assertEquals(listOf(old, committed.copy(clientMsgId = "c1")), merged)
    }

    @Test fun seqCollisionDoesNotLoseAnotherQueuedQuestion() {
        val queued = row(3, "user", "next question", "c2").copy(pending = true)
        val answer = row(3, "model", "answer")
        val merged = mergeCommittedTurns(listOf(queued), listOf(answer), "c1")
        assertEquals(listOf(answer, queued.copy(seq = 4)), merged)
    }

    @Test fun upsertPreservesMeasuredThinkingButUpdatesUsage() {
        val local = row(3, "model", "answer").copy(thinkingMs = 200)
        val committed = local.copy(thinkingMs = 0, cacheRead = 80)
        assertEquals(listOf(committed.copy(thinkingMs = 200)), mergeCommittedTurns(listOf(local), listOf(committed), "c1"))
    }

    @Test fun websocketBatchDecodesEveryFooterFieldAndDefaultsForOldDaemon() {
        val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(AiOutput::class.java)
        val frame = adapter.fromJson("""{"kind":"done","session_id":"s","seq":42,"turns_synced":true,
            "turns":[{"session_id":"s","seq":42,"role":"assistant","text":"ok","created_at":123,
            "input_tokens":100,"output_tokens":5,"cache_read_tokens":80,"cache_write_tokens":2,
            "reasoning_tokens":3,"reasoning_content":"real","input_includes_cache":true,
            "model":"big-pickle","duration_ms":9000,"generation_ms":4000}]}""")!!
        assertTrue(frame.turnsSynced)
        val turn = frame.turns.single()
        assertEquals(42L, turn.seq)
        assertEquals(80, turn.cacheRead)
        assertEquals(2, turn.cacheWrite)
        assertEquals(3, turn.reasoningTokens)
        assertEquals("real", turn.reasoningContent)
        assertTrue(turn.inputIncludesCache)
        assertEquals(9000L, turn.durationMs)
        assertEquals(4000L, turn.generationMs)
        assertEquals("big-pickle", turn.model)
        assertFalse(adapter.fromJson("""{"kind":"done"}""")!!.turnsSynced)
    }
}
