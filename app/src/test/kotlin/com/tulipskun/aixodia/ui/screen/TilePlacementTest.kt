package com.tulipskun.aixodia.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a tile's pixels land in the picture.
 *
 * Every tile but the edge ones is a full square, so an edge tile that is placed
 * by its own height looks right in a quick test and wrong on a phone: it slides
 * up the picture, leaving a stripe of old pixels where it should be and a gap at
 * the real edge. These use a 1280x720 picture with 64-pixel tiles, which is the
 * shape that goes wrong — the last row is only 16 pixels high.
 */
class TilePlacementTest {

    @Test
    fun a_full_tile_lands_on_its_own_cell() {
        val rect = ScreenSurface.tileRect(col = 3, row = 2, cell = 64, width = 64, height = 64)
        assertEquals(192f, rect.left, 0.01f)
        assertEquals(128f, rect.top, 0.01f)
        assertEquals(256f, rect.right, 0.01f)
        assertEquals(192f, rect.bottom, 0.01f)
    }

    @Test
    fun a_clipped_bottom_tile_lands_at_the_bottom() {
        // Row 11 of a 720-high picture: 11 * 64 = 704, and only 16 pixels are
        // left, so the tile is 1280 wide by 16 high.
        val rect = ScreenSurface.tileRect(col = 0, row = 11, cell = 64, width = 64, height = 16)
        assertEquals(704f, rect.top, 0.01f)
        assertEquals(720f, rect.bottom, 0.01f)
        // Not the 176 the tile's own height would have given.
        assertEquals(false, rect.top == 176f)
    }

    @Test
    fun every_row_of_a_720_picture_covers_the_whole_picture() {
        // Row by row, the tiles must tile the height exactly: no overlap, no gap,
        // and the last one reaching 720.
        val cell = 64
        val height = 720
        var covered = 0f
        for (row in 0 until (height + cell - 1) / cell) {
            val tileHeight = minOf(cell, height - row * cell)
            val rect = ScreenSurface.tileRect(0, row, cell, cell, tileHeight)
            assertEquals("row $row starts where the previous one ended", covered, rect.top, 0.01f)
            covered = rect.bottom
        }
        assertEquals(720f, covered, 0.01f)
    }

    @Test
    fun a_clipped_right_tile_lands_at_the_right_edge() {
        // 1280 wide: column 19 is the last, holding 64 pixels (1280 = 20 * 64),
        // and a 1000-wide picture would leave column 15 with 40 pixels.
        val rect = ScreenSurface.tileRect(col = 15, row = 0, cell = 64, width = 40, height = 64)
        assertEquals(960f, rect.left, 0.01f)
        assertEquals(1000f, rect.right, 0.01f)
    }

    @Test
    fun a_tile_is_never_placed_by_its_own_height() {
        // The bug in one line: with the bitmap's height for the cell, the last
        // row of a 720 picture lands at 11 * 16 = 176 and the picture's bottom
        // 16 pixels are never drawn.
        val correct = ScreenSurface.tileRect(0, 11, 64, 64, 16)
        val wrong = 11f * 16f
        assertEquals(704f, correct.top, 0.01f)
        assertEquals(true, correct.top != wrong)
    }
}
