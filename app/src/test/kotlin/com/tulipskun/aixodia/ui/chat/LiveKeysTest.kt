package com.tulipskun.aixodia.ui.chat

import com.tulipskun.aixodia.data.model.ToolStep
import com.tulipskun.aixodia.ui.chat.ChatViewModel.LiveBlock
import com.tulipskun.aixodia.ui.display.ThinkPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keys the live thread draws its items under.
 *
 * A lazy list does not fall back to a duplicate key, it throws on one, so what
 * is being asserted here is not stability but survival: two blocks that arrive
 * in the same millisecond, or two waits the engine never named, must not be
 * handed the same name.
 */
class LiveKeysTest {
    @Test fun everyItemGetsItsOwnKey() {
        val blocks = listOf(
            LiveBlock.Say("first", atMs = 1_000L),
            LiveBlock.Say("second", atMs = 1_000L),
            LiveBlock.Think(ThinkPhase.WAITING, startedAtMs = 7L),
            LiveBlock.Think(ThinkPhase.THINKING, startedAtMs = 7L),
            LiveBlock.Compact(2, 3, listOf("older"), atMs = 9L),
            LiveBlock.Compact(2, 3, listOf("older"), atMs = 9L),
        )
        val keys = uniqueLiveKeys(blocks)
        assertEquals(blocks.size, keys.size)
        assertEquals(blocks.size, keys.distinct().size)
    }

    @Test fun anEngineBlockKeepsItsOwnIdAsItsKey() {
        val blocks = listOf(LiveBlock.Say("narration", atMs = 1_000L, id = "n1"))
        assertEquals(listOf("live-say-n1"), uniqueLiveKeys(blocks))
    }

    @Test fun rowsPairEveryBlockWithItsOwnKey() {
        val blocks = listOf(
            LiveBlock.Say("first", atMs = 1_000L),
            LiveBlock.Think(ThinkPhase.WAITING, startedAtMs = 7L),
            LiveBlock.Think(ThinkPhase.THINKING, startedAtMs = 7L),
            LiveBlock.Act(ToolStep(id = "call-1", name = "bash", args = "ls")),
            LiveBlock.Compact(2, 3, listOf("older"), atMs = 9L),
            LiveBlock.Say("last", atMs = 1_000L),
        )
        val rows = liveRows(blocks)
        assertEquals(blocks, rows.map { it.block })
        assertEquals(blocks.size, rows.map { it.key }.distinct().size)
    }

    @Test fun anEmptyTimelineHasNoRows() {
        assertTrue(liveRows(emptyList()).isEmpty())
    }

    @Test fun aToolCardIsKeyedByTheCallItBelongsTo() {
        val blocks = listOf(
            LiveBlock.Act(ToolStep(id = "call-1", name = "bash", args = "ls")),
            LiveBlock.Act(ToolStep(id = "call-2", name = "bash", args = "pwd")),
        )
        assertEquals(listOf("live-act-call-1", "live-act-call-2"), uniqueLiveKeys(blocks))
    }

    @Test fun theKeyOfASettledBlockDoesNotMoveWhenANewOneArrives() {
        // The reader has a tool card open; the answer streams a new block under
        // it. The card keeps its name, or Compose throws away what it shows.
        val before = LiveBlock.Act(ToolStep(id = "call-1", name = "bash", args = "ls"))
        val first = uniqueLiveKeys(listOf(before))
        val later = uniqueLiveKeys(listOf(before, LiveBlock.Say("words", atMs = 2_000L)))
        assertEquals(first[0], later[0])
        assertTrue(later[1].startsWith("live-say-"))
    }
}
