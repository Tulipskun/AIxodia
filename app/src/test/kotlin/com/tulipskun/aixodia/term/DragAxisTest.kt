package com.tulipskun.aixodia.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.tan

/**
 * The drawer is a horizontal gesture and the page a vertical one; these pin
 * down where the page lets go of a drag. The cut is not 45° — a sideways sweep
 * with a thumb carries some downward drift, and that drift belongs to the
 * drawer until the drag is clearly vertical.
 */
class DragAxisTest {

    private val slop = 8f

    /** A drag at [degrees] below horizontal, far past the slop. */
    private fun at(degrees: Double): DragAxis? =
        dragAxis(totalDx = 200f, totalDy = (200.0 * tan(Math.toRadians(degrees))).toFloat(), slop = slop)

    @Test
    fun aSwipeInsideTheSlopIsUndecided() {
        assertNull(dragAxis(3f, 3f, slop))
        assertNull(dragAxis(slop - 0.1f, 0f, slop))
    }

    @Test
    fun aFlatSwipeIsTheDrawers() {
        assertEquals(DragAxis.Horizontal, at(0.0))
        assertEquals(DragAxis.Horizontal, at(20.0))
    }

    @Test
    fun aDiagonalSwipePastFortyFiveDegreesIsStillTheDrawers() {
        assertEquals(DragAxis.Horizontal, at(50.0))
        assertEquals(DragAxis.Horizontal, at(60.0))
    }

    @Test
    fun aSwipeThatIsClearlyVerticalIsThePages() {
        assertEquals(DragAxis.Vertical, at(70.0))
        assertEquals(DragAxis.Vertical, at(85.0))
    }

    @Test
    fun anEvenTieOnTheFirstFrameGoesToTheDrawer() {
        assertEquals(DragAxis.Horizontal, dragAxis(20f, 20f, slop))
    }

    @Test
    fun aJitteryStartDoesNotLatchThePage() {
        // The first move of a sideways sweep picked up a little extra downward
        // drift; the old 45° snapshot read that as a scroll and kept it.
        assertEquals(DragAxis.Horizontal, dragAxis(30f, 34f, slop))
    }
}
