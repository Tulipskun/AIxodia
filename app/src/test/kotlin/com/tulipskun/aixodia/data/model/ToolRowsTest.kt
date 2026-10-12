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
