package com.tulipskun.aixodia.ui.display

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one question the edge guard asks about a drag: where did the finger come
 * down? Everything the drawer is still allowed to do — and everything it must
 * not be handed, down to the first millimetre of a peek — is that answer.
 */
class EdgeDrawerTest {
    // A 1000px wide surface, so the band is its left 150px.
    private fun inBand(downX: Float, width: Int = 1000) = isInDrawerEdgeBand(downX, width)

    @Test fun theBandIsTheLeftFifteenPercent() = assertTrue(DRAWER_EDGE_SHARE == 0.15f)

    @Test fun aPressAtTheBorderIsInsideTheBand() = assertTrue(inBand(12f))

    @Test fun theLastPixelOfTheBandIsStillTheEdge() = assertTrue(inBand(150f))

    @Test fun aPressOnePixelOutsideTheBandIsNotTheEdge() = assertFalse(inBand(151f))

    @Test fun aPressOverTheContentIsNotTheEdge() = assertFalse(inBand(600f))

    @Test fun theBandScalesWithTheSurface() {
        // 400px wide: the band is 60px, so a press at 70 has left it.
        assertTrue(inBand(55f, width = 400))
        assertFalse(inBand(70f, width = 400))
    }

    // A 1000 x 2000 surface whose bottom 400px is the composer bar: the band
    // runs down the left edge only as far as that bar begins.
    private fun press(downX: Float, downY: Float, reserved: Int = 400) =
        isDrawerEdgePress(downX, downY, 1000, 2000, reserved)

    @Test fun aPressBesideTheThreadIsTheEdge() = assertTrue(press(12f, 300f))

    @Test fun theLastRowOfTheThreadIsStillTheEdge() = assertTrue(press(12f, 1600f))

    @Test fun aPressOverTheComposerBarIsNotTheEdge() = assertFalse(press(12f, 1601f))

    @Test fun aPressDeepInTheComposerBarIsNotTheEdge() = assertFalse(press(12f, 1900f))

    @Test fun aBarThatIsTheWholeSurfaceLeavesNoBandAtAll() =
        assertFalse(isDrawerEdgePress(0f, 1f, 1000, 300, 400))

    @Test fun nothingReservedKeepsTheFullHeight() =
        assertTrue(isDrawerEdgePress(12f, 1999f, 1000, 2000, 0))

    @Test fun aTerminalFooterOfTwoRowsStillLeavesTheGridTheEdge() {
        // 2000px tall, the terminal floor measured at 620px from the bottom of
        // the window: the last row above it is still a swipe for the menu.
        assertTrue(isDrawerEdgePress(12f, 1380f, 1000, 2000, 620))
        assertFalse(isDrawerEdgePress(12f, 1381f, 1000, 2000, 620))
    }
}
