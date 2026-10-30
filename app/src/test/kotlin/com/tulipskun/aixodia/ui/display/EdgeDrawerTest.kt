package com.tulipskun.aixodia.ui.display

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one decision the edge drawer makes, tested on its own: a press begun
 * inside the left band, dragged right past the slop and further sideways than
 * up-down. Everything the gesture must not steal — a swipe over a message, a
 * scroll, a selection — is a case where this returns false.
 */
class EdgeDrawerTest {
    // A 1000px wide surface with a 30px slop, so the band is 150px.
    private fun opens(downX: Float, dx: Float, dy: Float, width: Int = 1000) =
        edgeSwipeOpensDrawer(downX = downX, widthPx = width, dx = dx, dy = dy, slopPx = 30f)

    @Test fun theBandIsTheLeftFifteenPercent() = assertTrue(DRAWER_EDGE_SHARE == 0.15f)

    @Test fun aSwipeBegunAtTheBorderOpensTheDrawer() = assertTrue(opens(downX = 12f, dx = 120f, dy = 4f))

    @Test fun theLastPixelOfTheBandStillCountsAsTheEdge() = assertTrue(opens(downX = 150f, dx = 120f, dy = 0f))

    @Test fun aPressOnePixelOutsideTheBandIsNotAnEdgeSwipe() =
        assertFalse(opens(downX = 151f, dx = 300f, dy = 0f))

    @Test fun aSwipeBegunOverTheContentNeverOpensIt() =
        assertFalse(opens(downX = 600f, dx = 200f, dy = 0f))

    @Test fun aDragShorterThanTheSlopIsStillATap() =
        assertFalse(opens(downX = 5f, dx = 29f, dy = 0f))

    @Test fun aScrollBegunNearTheEdgeIsNotASwipe() =
        assertFalse(opens(downX = 5f, dx = 40f, dy = 400f))

    @Test fun aDragBackToTheEdgeClosesNothingByItself() =
        assertFalse(opens(downX = 5f, dx = -120f, dy = 0f))

    @Test fun aNarrowSurfaceKeepsItsProportionOfBand() {
        // 400px wide: the band is 60px, so a press at 70 has left it.
        assertFalse(opens(downX = 70f, dx = 200f, dy = 0f, width = 400))
        assertTrue(opens(downX = 55f, dx = 200f, dy = 0f, width = 400))
    }
}
