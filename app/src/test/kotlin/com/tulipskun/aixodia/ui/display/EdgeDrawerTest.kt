package com.tulipskun.aixodia.ui.display

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two questions the edge guard asks about a drag, answered on their own:
 * where did the finger come down, and is this drag sideways? Everything the
 * drawer is still allowed to do — and everything it must not be handed — is one
 * of these returning false.
 */
class EdgeDrawerTest {
    // A 1000px wide surface, so the band is its left 150px, and a 30px slop.
    private fun inBand(downX: Float, width: Int = 1000) = isInDrawerEdgeBand(downX, width)
    private fun sideways(dx: Float, dy: Float) = isSidewaysDrag(dx, dy, 30f)

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

    @Test fun aDragPastTheSlopSidewaysIsASwipe() = assertTrue(sideways(120f, 4f))

    @Test fun aDragShorterThanTheSlopIsStillAHold() = assertFalse(sideways(29f, 0f))

    @Test fun aScrollIsNotASwipe() = assertFalse(sideways(40f, 400f))

    @Test fun aSwipeBackToTheEdgeIsStillSideways() = assertTrue(sideways(-120f, 8f))

    @Test fun aDiagonalDriftOffAVerticalScrollIsNotASwipe() = assertFalse(sideways(35f, 60f))
}
