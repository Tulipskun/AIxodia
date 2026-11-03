package com.tulipskun.aixodia.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolRowsTest {
    @Test fun toolCallSplitsNameArgsAndPreamble() {
        val view = toolRowView("tool_call", """{"id":"c1","name":"current_time","arguments":"{}","content":"checking"}""")
        assertEquals(ToolRowView("checking", "current_time", "{}"), view)
    }

    @Test fun toolResultShowsOutput() {
        val view = toolRowView("tool_result", """{"tool_call_id":"c1","name":"current_time","content":"10:00"}""")
        assertEquals(ToolRowView("10:00", "current_time", ""), view)
    }

    @Test fun unreadableToolJsonIsKept() {
        assertEquals(ToolRowView("not json", "", ""), toolRowView("tool_call", "not json"))
    }

    @Test fun ordinaryRowsAreUntouched() {
        assertEquals(ToolRowView("hi", "", ""), toolRowView("user", "hi"))
    }

    @Test fun toolCallCarriesItsRoundsReasoning() {
        val view = toolRowView(
            "tool_call",
            """{"id":"c1","name":"bash","arguments":"{}","content":"checking","reasoning":"weighed it first"}""",
        )
        assertEquals("weighed it first", view.reasoning)
    }

    @Test fun toolResultHasNoReasoning() {
        // Reasoning rides on the call, never on the result that answers it.
        val view = toolRowView("tool_result", """{"tool_call_id":"c1","name":"bash","content":"ok","reasoning":"x"}""")
        assertEquals("", view.reasoning)
    }
}

class ToolDisplayTest {
    private fun msg(seq: Long, role: String, text: String = "", toolName: String = "", toolArgs: String = "", model: String = "") =
        ChatMessage(id = "s:$seq", sessionId = "s", seq = seq, role = role, text = text,
            createdAt = seq * 1000, toolName = toolName, toolArgs = toolArgs, model = model)

    @Test fun bashArgsHumanizeToCommand() {
        assertEquals("pwd", humanToolArgs("""{"command":"pwd"}"""))
        assertEquals("/a/b", humanToolArgs("""{"filePath":"/a/b"}"""))
        assertEquals("{}", humanToolArgs("{}"))
        assertEquals("not json", humanToolArgs("not json"))
    }

    @Test fun toolCallArgsAreHumanized() {
        val view = toolRowView("tool_call", """{"id":"c1","name":"bash","arguments":"{\"command\": \"pwd\"}"}""")
        assertEquals("bash", view.toolName)
        assertEquals("pwd", view.toolArgs)
    }

    @Test fun adjacentCallAndResultMerge() {
        val merged = mergeToolPairs(listOf(
            msg(8, "tool_call", toolName = "bash", toolArgs = "pwd"),
            msg(9, "tool_result", "/kaggle/working\n", toolName = "bash"),
            msg(10, "assistant", "done", model = "m1"),
        ))
        assertEquals(2, merged.size)
        assertEquals("tool_result", merged[0].role)
        assertEquals("bash", merged[0].toolName)
        assertEquals("pwd", merged[0].toolArgs)
        assertEquals("/kaggle/working\n", merged[0].text)
        assertEquals("done", merged[1].text)
    }

    @Test fun loneCallAndOrphanResultPassThrough() {
        val out = mergeToolPairs(listOf(
            msg(9, "tool_result", "out"),
            msg(10, "tool_call", toolName = "bash", toolArgs = "tree"),
        ))
        assertEquals(2, out.size)
        assertEquals("tool_result", out[0].role)
        assertEquals("tool_call", out[1].role)
    }
}

class ToolMergeTest {
    private fun msg(seq: Long, role: String, text: String = "", toolName: String = "", toolArgs: String = "") =
        ChatMessage(id = "s:$seq", sessionId = "s", seq = seq, role = role, text = text,
            createdAt = seq * 1000, toolName = toolName, toolArgs = toolArgs)

    @Test fun parallelCallsPairInOrder() {
        val merged = mergeToolPairs(listOf(
            msg(1, "tool_call", toolName = "bash", toolArgs = "pwd"),
            msg(2, "tool_call", toolName = "read", toolArgs = "/x"),
            msg(3, "tool_result", "w1", toolName = "bash"),
            msg(4, "tool_result", "f1", toolName = "read"),
        ))
        assertEquals(2, merged.size)
        assertEquals("pwd", merged[0].toolArgs)
        assertEquals("w1", merged[0].text)
        assertEquals("read", merged[1].toolName)
        assertEquals("f1", merged[1].text)
    }

    @Test fun midRoundProseIsKeptAboveOutput() {
        val merged = mergeToolPairs(listOf(
            msg(1, "tool_call", "Running it now.", toolName = "bash", toolArgs = "pwd"),
            msg(2, "tool_result", "w1", toolName = "bash"),
        ))
        assertEquals(1, merged.size)
        assertEquals("Running it now.\n\nw1", merged[0].text)
    }

    @Test fun mergedStepKeepsTheCallsOwnReasoning() {
        // The round's thinking rides on the call row; the result row has none.
        // The merged step must carry it so the stored thread shows one thought
        // block per request, above each tool card.
        val call = ChatMessage(
            id = "s:1", sessionId = "s", seq = 1, role = "tool_call", text = "",
            createdAt = 1000, toolName = "bash", toolArgs = "pwd",
            reasoningContent = "thought hard", thinkingMs = 1_500L,
        )
        val result = ChatMessage(
            id = "s:2", sessionId = "s", seq = 2, role = "tool_result", text = "w1",
            createdAt = 2000, toolName = "bash",
        )
        val merged = mergeToolPairs(listOf(call, result))
        assertEquals(1, merged.size)
        assertEquals("thought hard", merged[0].reasoningContent)
        assertEquals(1_500L, merged[0].thinkingMs)
    }

    private fun blocked(seq: Long, role: String, callId: String, text: String = "", toolName: String = "") =
        ChatMessage(
            id = "s:$seq", sessionId = "s", seq = seq, role = role, text = text,
            createdAt = seq * 1000, toolName = toolName,
            block = """{"id":"$callId","type":"tool","state":"done","name":"$toolName"}""",
        )

    @Test fun resultsPairByCallIdNotPosition() {
        // Two calls of the same tool ran side by side and answered out of
        // order. Only the id says which output belongs to which command.
        val merged = mergeToolPairs(listOf(
            blocked(1, "tool_call", "c1", toolName = "bash", text = ""),
            blocked(2, "tool_call", "c2", toolName = "bash", text = ""),
            blocked(3, "tool_result", "c2", text = "second", toolName = "bash"),
            blocked(4, "tool_result", "c1", text = "first", toolName = "bash"),
        ))
        assertEquals(2, merged.size)
        assertEquals("first", merged[0].text)
        assertEquals("second", merged[1].text)
    }

    @Test fun resultWithoutItsCallOnThePagePassesThrough() {
        // The call sits above the loaded page; pairing it positionally to the
        // only waiting call would glue two different steps together.
        val merged = mergeToolPairs(listOf(
            blocked(1, "tool_call", "c1", toolName = "bash"),
            blocked(2, "tool_result", "c9", text = "orphan", toolName = "bash"),
        ))
        assertEquals(2, merged.size)
        assertEquals("tool_call", merged[0].role)
        assertEquals("tool_result", merged[1].role)
    }
}
