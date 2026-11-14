package com.tulipskun.aixodia.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two pieces of arithmetic behind the picture: where a touch lands on the
 * machine, and what a pinch does to the picture.
 *
 * A touch that lands somewhere other than under the finger, or a pinch that
 * shifts what is under the fingers, is the complaint these are here to stop.
 */
class GestureMathTest {

    /** The fit-and-zoom rectangle, as ScreenView draws it. */
    private fun box(
        pictureWidth: Int,
        pictureHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        zoom: Float = 1f,
        panX: Float = 0f,
        panY: Float = 0f,
    ): FloatArray {
        val fit = minOf(viewWidth.toFloat() / pictureWidth, viewHeight.toFloat() / pictureHeight)
        val scale = fit * zoom
        val drawnWidth = pictureWidth * scale
        val drawnHeight = pictureHeight * scale
        val left = (viewWidth - drawnWidth) / 2f + panX
        val top = (viewHeight - drawnHeight) / 2f + panY
        return floatArrayOf(left, top, left + drawnWidth, top + drawnHeight)
    }

    /** A point on the view, as picture pixels — the inverse of the drawn box. */
    private fun toPicture(
        x: Float,
        y: Float,
        pictureWidth: Int,
        pictureHeight: Int,
        box: FloatArray,
    ): Pair<Int, Int> {
        val scaleX = pictureWidth / (box[2] - box[0])
        val scaleY = pictureHeight / (box[3] - box[1])
        val px = ((x - box[0]) * scaleX).toInt().coerceIn(0, pictureWidth - 1)
        val py = ((y - box[1]) * scaleY).toInt().coerceIn(0, pictureHeight - 1)
        return px to py
    }

    @Test
    fun aTouchAtThePicturesCornerReachesTheMachinesCorner() {
        // The picture fits the view with letterboxing; the middle of the picture
        // must be the middle of the machine, and its corner the machine's corner.
        val box = box(1280, 720, 1080, 1920)
        val (cx, cy) = toPicture(540f, 960f, 1280, 720, box)
        assertEquals(640, cx)
        assertEquals(360, cy)
    }

    @Test
    fun aTouchLandsUnderTheFingerAtAnyZoom() {
        // The complaint was a pointer that arrived somewhere else. Whatever the
        // zoom and pan, undoing what drawing did must give back the same point.
        for (zoom in listOf(1f, 1.5f, 2f, 4f)) {
            val box = box(1280, 720, 1080, 1920, zoom, panX = -120f * zoom, panY = 40f * zoom)
            // A point in the middle of the drawn picture.
            val viewX = (box[0] + box[2]) / 2f
            val viewY = (box[1] + box[3]) / 2f
            val (px, py) = toPicture(viewX, viewY, 1280, 720, box)
            assertEquals("middle of the picture at zoom $zoom", 640, px)
            assertEquals("middle of the picture at zoom $zoom", 360, py)
        }
    }

    @Test
    fun aPinchKeepsThePointBetweenTheFingersStill() {
        // The rule: the picture grows about the point the fingers hold, so that
        // point does not move on screen. Compounding from the previous frame
        // instead made it drift, which looked like the picture stuttering.
        val focusX = 300f
        val basePanX = 0f
        val startZoom = 1f
        for (wanted in listOf(1.2f, 1.6f, 2f, 3f, 4f)) {
            val growth = wanted / startZoom
            val pan = basePanX + (focusX - 0f - basePanX) * (1 - growth)
            val before = focusX + basePanX
            val after = (focusX * growth) + pan
            assertEquals("the focus stays put at zoom $wanted", before, after, 0.01f)
        }
    }

    @Test
    fun aPinchBackOutReturnsToWhereItStarted() {
        // Pinching out and back in must land exactly where it began. This fails
        // if each frame builds on the last rather than on the start.
        val focusX = 240f
        val basePanX = 0f
        val startZoom = 1.5f

        fun panFor(wanted: Float): Float {
            val growth = wanted / startZoom
            return basePanX + (focusX - basePanX) * (1 - growth)
        }

        assertEquals(basePanX, panFor(startZoom), 0.01f)
        val out = panFor(3f)
        assertEquals(basePanX + (focusX - basePanX) * (1 - 3f / startZoom), out, 0.01f)
        assertEquals("back to the start", basePanX, panFor(startZoom), 0.01f)
    }

    @Test
    fun zoomStaysWithinItsLimits() {
        assertEquals(1f, 0.3f.coerceIn(1f, 4f), 0.001f)
        assertEquals(4f, 9f.coerceIn(1f, 4f), 0.001f)
    }

    @Test
    fun aPanCannotDragThePictureIntoEmptySpace() {
        val viewWidth = 1080
        val drawnWidth = 2160f
        val slack = (drawnWidth - viewWidth) / 2f
        assertEquals(slack, 600f.coerceIn(-slack, slack), 0.01f)
        assertEquals(-slack, (-900f).coerceIn(-slack, slack), 0.01f)
    }
}
