package com.tulipskun.aixodia.ui.display

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One question the floor guard asks about a drag: did the finger come down on
 * the strip of the bottom that belongs to another control? Everything the
 * drawer is still allowed to do — a full-width swipe begun anywhere on the page
 * above — is that returning false.
 */
class DrawerFloorTest {
    // A 2000px tall surface whose bottom 400px is the composer bar.
    private fun onFloor(downY: Float, reserved: Int = 400) =
        isOnDrawerFloor(downY, 2000, reserved)

    @Test fun aPressOverTheComposerBarIsTheFloor() = assertTrue(onFloor(1700f))

    @Test fun aPressDeepInTheComposerBarIsTheFloor() = assertTrue(onFloor(1990f))

    @Test fun theFirstRowOfTheBarIsTheFloor() = assertTrue(onFloor(1601f))

    @Test fun theLastRowOfTheThreadIsNotTheFloor() = assertFalse(onFloor(1600f))

    @Test fun aSwipeBegunMidPageIsTheDrawerNotTheFloor() {
        // The point of the full-width restore: a drag started a hand's width
        // inside the screen, or out near the right edge, belongs to the menu.
        assertFalse(onFloor(300f))
        assertFalse(onFloor(1000f))
    }

    @Test fun aTerminalFooterOfTwoRowsStillLeavesTheGridThePage() =
        assertFalse(onFloor(1380f, reserved = 620))

    @Test fun theRowUnderATerminalFooterIsTheFloor() =
        assertTrue(onFloor(1381f, reserved = 620))

    @Test fun nothingReservedLeavesNoFloorAtAll() = assertFalse(onFloor(1999f, reserved = 0))

    @Test fun aBarTallerThanTheSurfaceIsTheWholeSurface() =
        assertTrue(onFloor(1f, reserved = 2400))

    @Test fun aZeroHeightSurfaceHasNoFloor() =
        assertFalse(isOnDrawerFloor(0f, 0, 0))
}
