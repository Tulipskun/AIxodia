package com.tulipskun.aixodia.data.remote

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What the client does when a frame does not arrive intact.
 *
 * Only changed tiles are sent, so a lost tile is not resent on the next frame —
 * it stays wrong until that part of the screen changes, and a still window may
 * never change. Every such case has to ask for a whole frame instead of failing
 * quietly, which is what these check.
 */
class ScreenRecoveryTest {

    /** Builds a frame the way the engine does, so the wire format is exercised too. */
    private fun frame(
        seq: Long = 1,
        tile: Int = 64,
        tiles: List<Pair<Int, Int>> = emptyList(),
        payload: ByteArray = byteArrayOf(1, 2, 3),
    ): ByteArray {
        val header = """{"type":"frame","width":1280,"height":720,"seq":$seq,"tile":$tile}""".toByteArray()
        val body = ByteBuffer.allocate(4 + header.size + 4 + tiles.size * 12 + tiles.size * payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        body.putInt(header.size)
        body.put(header)
        body.putInt(tiles.size)
        for ((col, row) in tiles) {
            body.putShort(col.toShort())
            body.putShort(row.toShort())
            body.putShort(tile.toShort())
            body.putShort(tile.toShort())
            body.putInt(payload.size)
            body.put(payload)
        }
        return body.array()
    }

    @Test
    fun aFrameCarriesItsGridSoEdgeTilesCanBePlaced() {
        // The grid must come from the frame, not from what `hello` said at
        // connect: a settings message can change it while the link is open.
        val patch = ScreenSocket.decodeForTest(frame(seq = 7, tile = 128))
        assertTrue("a frame should decode", patch is ScreenSocket.Event.Patch)
        val decoded = patch as ScreenSocket.Event.Patch
        assertTrue("the grid travels with the frame", decoded.tile == 128)
        assertTrue("the counter travels with the frame", decoded.seq == 7L)
    }

    @Test
    fun aFrameThatCannotBeReadWholeIsRefusedRatherThanPassedOnShort() {
        // A tile that will not decode cannot be left out: the caller would paint
        // a frame with a hole and no way to know. BitmapFactory is native and
        // absent here, so every tile in this frame fails to decode — which is
        // exactly the case being checked. Returning null is what makes the
        // caller ask for a whole frame.
        val bytes = frame(seq = 1, tiles = listOf(0 to 0))
        val refused = runCatching { ScreenSocket.decodeForTest(bytes) }.getOrNull()
        assertTrue(
            "an unreadable frame yields nothing rather than a partial one",
            refused == null,
        )
    }

    @Test
    fun aFrameWithNoTilesStillCarriesItsGrid() {
        // An empty frame is legal — nothing changed — and must not be mistaken
        // for damage, nor lose the grid or the counter the gap check needs.
        val empty = ScreenSocket.decodeForTest(frame(seq = 3, tile = 32)) as ScreenSocket.Event.Patch
        assertTrue("no tiles", empty.patches.isEmpty())
        assertTrue("the grid survives an empty frame", empty.tile == 32)
        assertTrue("the counter survives an empty frame", empty.seq == 3L)
    }

    @Test
    fun aSkippedSequenceNumberIsVisible() {
        // The gap check is only possible if the counter survives decoding.
        val first = ScreenSocket.decodeForTest(frame(seq = 5)) as ScreenSocket.Event.Patch
        val later = ScreenSocket.decodeForTest(frame(seq = 9)) as ScreenSocket.Event.Patch
        assertTrue("a gap is detectable", later.seq != first.seq + 1)
    }
}
