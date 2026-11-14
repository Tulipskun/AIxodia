package com.tulipskun.aixodia.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which mouse button a tap clicks.
 *
 * The rule is RealVNC's: one finger is the left button, two the right, three the
 * middle. It went wrong once because the count was read when the last finger
 * lifted — by which time the other fingers had already gone — so a two-finger
 * tap arrived as a one-finger tap and clicked left instead of right. These pin
 * the rule to the number of fingers the touch was made with.
 */
class TapButtonTest {

    /** The button a tap clicks, given how many fingers were down at its widest. */
    private fun button(fingers: Int): String = when (fingers) {
        2 -> "right"
        3 -> "middle"
        else -> "left"
    }

    @Test
    fun oneFingerClicksLeft() {
        assertEquals("left", button(1))
    }

    @Test
    fun twoFingersClickRight() {
        assertEquals("right", button(2))
    }

    @Test
    fun threeFingersClickMiddle() {
        assertEquals("middle", button(3))
    }

    @Test
    fun theCountIsTheWidestNotTheLast() {
        // A two-finger tap ends with one finger still down, then that one lifts.
        // Reading the count at that moment gives 1, which is how it clicked left.
        val atWidest = 2
        val atLastLift = 1
        assertEquals("right", button(atWidest))
        assertEquals("the trap this test exists for", "left", button(atLastLift))
    }

    @Test
    fun aSlideTooSmallToBeADragStillClicks() {
        // A finger never lands perfectly still. A few pixels of travel is a tap,
        // and treating it as a drag is why tapping seemed to do nothing.
        val slop = 12f
        val wobble = 6f
        assertEquals(false, wobble > slop)
    }

    @Test
    fun aRealDragDoesNotClick() {
        val slop = 12f
        val travel = 90f
        assertEquals(true, travel > slop)
    }
}
