package com.tulipskun.aixodia.ui.display

import com.tulipskun.aixodia.data.model.Block
import com.tulipskun.aixodia.data.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The block contract: parsing never throws, old rows fall back to the role,
 * and each side of a tool step fills what the other leaves blank — so a new
 * engine type draws as a plain card instead of vanishing.
 */
class BlocksTest {

    private fun message(block: String = "") = ChatMessage(
        id = "s:1",
        sessionId = "s",
        seq = 1,
        role = "tool_result",
        text = "out",
        createdAt = 1_000L,
        toolName = "shell",
        toolArgs = "{command: \"pwd\"}",
        durationMs = 400L,
        block = block,
    )

    @Test
    fun `blank and broken blocks fall back to the role`() {
        assertNull(Block.parse(""))
        assertNull(Block.parse("   "))
        assertNull(Block.parse("not json"))
        assertNull(Block.parse("""{"id":"x"}"""))
    }

    @Test
    fun `a tool block parses with its state`() {
        val block = Block.parse(
            """{"id":"c1","type":"tool","state":"done","name":"shell","duration_ms":400}""",
        )!!
        assertEquals(Block.TOOL, block.type)
        assertEquals("done", block.state)
        assertEquals("c1", block.id)
    }

    @Test
    fun `an unseen type is still a block`() {
        val block = Block.parse("""{"id":"x1","type":"gallery","text":"hi"}""")!!
        assertEquals("gallery", block.type)
        assertEquals(false, block.known)
    }

    @Test
    fun `the row fills what the stored block leaves blank`() {
        val m = message("""{"id":"c1","type":"tool","state":"done","name":"shell"}""")
        val block = Block.parse(m.block)
        assertEquals("shell", toolCardName(m, block))
        // The result row carries no arguments; the merged call row does.
        assertEquals("{command: \"pwd\"}", toolCardArgs(m, block))
        // The stored measurement wins over the block's.
        assertEquals(400L, toolCardDurationMs(m, block))
    }

    @Test
    fun `the block fills what the row leaves blank`() {
        val m = message().copy(toolName = "", toolArgs = "", durationMs = 0L)
        val block = Block.parse(
            """{"id":"c1","type":"tool","state":"running","name":"shell","arguments":"pwd","duration_ms":9}""",
        )
        assertEquals("shell", toolCardName(m, block))
        assertEquals("pwd", toolCardArgs(m, block))
        assertEquals(9L, toolCardDurationMs(m, block))
    }

    @Test
    fun `a live tool step is done only when the engine settles it`() {
        val running = toolStepFor(
            Block.parse("""{"id":"c1","type":"tool","state":"running","name":"shell","arguments":"pwd"}""")!!,
            knownArgs = "",
            startedAtMs = 100L,
        )
        assertEquals(false, running.done)
        assertEquals("pwd", running.args)
        val done = toolStepFor(
            Block.parse("""{"id":"c1","type":"tool","state":"done","name":"shell","duration_ms":400}""")!!,
            knownArgs = "pwd",
            startedAtMs = 100L,
        )
        assertEquals(true, done.done)
        // A result frame never carries arguments; the running step lends them.
        assertEquals("pwd", done.args)
    }

    @Test
    fun `request ids follow the engine format`() {
        assertEquals("req1", Block.requestId(1))
        assertEquals("req12", Block.requestId(12))
    }
}
