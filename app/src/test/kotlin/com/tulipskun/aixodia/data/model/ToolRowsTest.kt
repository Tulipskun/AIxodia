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
