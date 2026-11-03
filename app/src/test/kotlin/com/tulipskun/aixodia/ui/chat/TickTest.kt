package com.tulipskun.aixodia.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two readings of the clock, and why they cannot be one reading.
 *
 * The live footer used to be handed the elapsed reading where a timestamp was
 * expected. `formatClock` turns a millisecond count into a date, so the phone
 * printed its own uptime as the time of day: "19:42" beside a message sent at
 * 05:13. Only the phone could show it, and only while a turn was running, which
 * is why nothing else caught it.
 */
class TickTest {

    @Test
    fun `the wall reading is a timestamp the clock formatter can use`() {
        val tick = Tick.now()
        // Anything before 2023 would format as a date near the epoch.
        assertTrue("wall clock looks like a duration: ${tick.wall}", tick.wall > 1_700_000_000_000L)
    }

    @Test
    fun `the elapsed reading is not a timestamp`() {
        val tick = Tick.now()
        // It counts from boot, so it is always the smaller of the two and is
        // never valid to pass to something that formats a date.
        assertTrue("elapsed looks like a timestamp: ${tick.elapsed}", tick.elapsed < tick.wall)
    }
}