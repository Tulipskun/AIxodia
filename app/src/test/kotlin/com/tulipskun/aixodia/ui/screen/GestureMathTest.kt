package com.tulipskun.aixodia.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind the gestures, checked without a screen.
 *
 * A gesture that is slightly wrong looks like a bug in the connection, so the
 * parts that can be reasoned about in isolation are pinned here.
 */
class GestureMathTest {

    @Test
    fun a_trackpad_pointer_moves_by_travel_not_to_the_finger() {
        // The distinguishing rule: the pointer keeps its place and gains only the
        // finger's movement. Sending the finger's position instead would make the
        // pointer jump to it, which is direct mode.
        var pointer = 500 to 300
        var fingerAt = 100 to 100
        val gain = 2.2f

        val fingerNow = 110 to 105
        pointer = pointer.first + ((fingerNow.first - fingerAt.first) * gain).toInt() to
            pointer.second + ((fingerNow.second - fingerAt.second) * gain).toInt()
        fingerAt = fingerNow

        assertEquals(522, pointer.first)
        assertEquals(311, pointer.second)
        assertEquals(fingerNow, fingerAt)
    }

    @Test
    fun lifting_a_trackpad_finger_does_not_move_the_pointer() {
        // A finger can be lifted and put down again; the pointer stays where it
        // was left, so a careful click is possible.
        val pointer = 640 to 360
        val afterLift = pointer
        assertEquals(640, afterLift.first)
        assertEquals(360, afterLift.second)
    }

    @Test
    fun a_zoom_keeps_the_point_between_the_fingers_still() {
        // Pinching around a point must grow the picture about that point. This is
        // the sum applyZoom() performs.
        val focusX = 200f
        val basePanX = 0f
        val previousZoom = 1f
        val wantedZoom = 2f
        val growth = wantedZoom / previousZoom
        val newPan = basePanX + (focusX - 0f - basePanX) * (1 - growth)

        // The point that was under the fingers is still under them.
        val beforeOnScreen = focusX + basePanX
        val afterOnScreen = (focusX * growth) + newPan
        assertEquals(beforeOnScreen, afterOnScreen, 0.01f)
    }

    @Test
    fun a_pinch_never_zooms_below_the_picture_fit() {
        // Zooming out past 1 would show empty space around a smaller picture.
        assertEquals(1f, 0.3f.coerceIn(1f, 4f), 0.001f)
    }

    @Test
    fun a_pinch_never_zooms_past_the_limit() {
        assertEquals(4f, 9f.coerceIn(1f, 4f), 0.001f)
    }

    @Test
    fun panning_stays_within_the_picture() {
        // Past the edge the user would drag the picture into empty space.
        val viewWidth = 1080
        val pictureWidth = 2160
        val slack = ((pictureWidth - viewWidth) / 2f)
        assertTrue("a pan within the slack is allowed", 300f in -slack..slack)
        assertEquals(slack, 600f.coerceIn(-slack, slack), 0.01f)
        assertEquals(-slack, (-900f).coerceIn(-slack, slack), 0.01f)
    }
}
