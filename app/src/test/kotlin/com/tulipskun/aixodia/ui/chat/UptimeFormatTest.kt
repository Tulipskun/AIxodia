package com.tulipskun.aixodia.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The corner counter, which now counts the machine rather than the engine.
 *
 * The engine restarted every few days, so its uptime never left the two-digit
 * hour and nobody looked at what the readout does past a day. A server's boot
 * does: a machine up for three days would have printed "04:15:30" — a number
 * that reads as though it had just come up, which is the opposite of what the
 * counter is there for.
 */
class UptimeFormatTest {

    private val nowMs = 1_760_000_000_000L
    private val nowS = nowMs / 1000

    @Test
    fun anUnknownClockReadsZeroAndNotTheEpoch() {
        assertEquals("00:00:00", formatUptime(0L, nowMs))
        assertEquals("00:00:00", formatUptime(-5L, nowMs))
    }

    @Test
    fun theSecondsTickUnderAMinute() {
        assertEquals("00:00:45", formatUptime(nowS - 45, nowMs))
    }

    @Test
    fun aMachineUpForDaysKeepsCountingHours() {
        assertEquals("02:05:00", formatUptime(nowS - 7_500, nowMs))
        assertEquals("24:00:00", formatUptime(nowS - 86_400, nowMs))
        assertEquals("72:30:00", formatUptime(nowS - (3 * 86_400 + 30 * 60), nowMs))
    }

    /**
     * A phone whose clock sits behind the server's would otherwise subtract a
     * boot time it has not reached yet. The counter must go still, not spin
     * through the epoch.
     */
    @Test
    fun aClockBehindTheServersGoesStill() {
        assertEquals("00:00:00", formatUptime(nowS + 600, nowMs))
    }
}
