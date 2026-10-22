package com.tulipskun.aixodia.data.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunFramesTest {
    @Test
    fun assistantBecomesADelta() {
        val frame = RunFrames.map("assistant", JSONObject("{\"text\":\"hi\"}"), "s1")
        assertNotNull(frame)
        assertEquals("delta", frame!!.kind)
        assertEquals("hi", frame.text)
        assertEquals("s1", frame.sessionId)
    }

    @Test
    fun reasoningIsATrace() {
        val frame = RunFrames.map("reasoning", JSONObject("{\"text\":\"think\"}"), "s1")
        assertEquals("trace", frame!!.kind)
        assertEquals("reasoning", frame.stage)
    }

    @Test
    fun aTurnRowIsCommittedButNotSynced() {
        val frame = RunFrames.map(
            "turn",
            JSONObject("{\"seq\":4,\"role\":\"model\",\"text\":\"part\"}"),
            "s1",
        )
        assertEquals("message", frame!!.kind)
        assertEquals(1, frame.turns.size)
        assertEquals(4L, frame.turns[0].seq)
        assertTrue(!frame.turnsSynced)
    }

    @Test
    fun theFinalTurnCarriesUsageAndClosesTheLiveTurn() {
        val json = JSONObject(
            """{"turn":{"seq":9,"role":"model","text":"answer","model":"m",
                "input_tokens":10,"output_tokens":3,"duration_ms":1200},
                "usage":{"prompt_tokens":10,"completion_tokens":3}}"""
        )
        val frame = RunFrames.map("final", json, "s1")!!
        assertEquals("answer", frame.text)
        assertEquals(9L, frame.seq)
        assertEquals("m", frame.model)
        assertEquals(10, frame.inputTokens)
        assertEquals(3, frame.outputTokens)
        assertTrue(frame.turnsSynced)
    }

    @Test
    fun doneAndCancelledAreTerminalDone() {
        assertEquals("done", RunFrames.map("status", JSONObject("{\"status\":\"done\"}"), "s1")!!.kind)
        val cancelled = RunFrames.map("status", JSONObject("{\"status\":\"cancelled\"}"), "s1")!!
        assertEquals("done", cancelled.kind)
        assertEquals("cancelled", cancelled.stage)
        assertTrue(RunFrames.terminal("cancelled"))
    }

    @Test
    fun failureAndInterruptionAreErrors() {
        val failed = RunFrames.map("status", JSONObject("{\"status\":\"failed\",\"error\":\"boom\"}"), "s1")!!
        assertEquals("error", failed.kind)
        assertEquals("boom", failed.text)
        val interrupted = RunFrames.map("status", JSONObject("{\"status\":\"interrupted\"}"), "s1")!!
        assertEquals("error", interrupted.kind)
        assertTrue(interrupted.text.contains("restarted"))
    }

    @Test
    fun aToolCallKeepsItsArguments() {
        val frame = RunFrames.map(
            "tool",
            JSONObject("{\"stage\":\"tool_call\",\"call_id\":\"c1\",\"name\":\"shell\",\"arguments\":\"{}\"}"),
            "s1",
        )!!
        assertEquals("trace", frame.kind)
        assertEquals("shell", frame.toolCall!!.name)
        assertEquals("c1", frame.toolCall.id)
    }

    @Test
    fun unknownKindsAndEmptyTextAreIgnored() {
        assertNull(RunFrames.map("assistant", JSONObject("{\"text\":\"\"}"), "s1"))
        assertNull(RunFrames.map("mystery", JSONObject("{}"), "s1"))
        assertNull(RunFrames.map("status", JSONObject("{\"status\":\"running\"}"), "s1"))
    }
}
