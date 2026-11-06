package com.tulipskun.aixodia.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retry curve both sockets wait on.
 *
 * Neither socket can be tried here without a machine at the other end of it,
 * and the two things worth being sure about are arithmetic: how long a dropped
 * link waits, and whether that wait is the same on every phone at once.
 */
class BackoffTest {
    @Test
    fun theJitterSpreadsTheWaitByAFifthEitherWay() {
        assertEquals(1_200L, jitteredBackoff(1_500L, 0.0))
        assertEquals(1_500L, jitteredBackoff(1_500L, 0.5))
        assertEquals(1_800L, jitteredBackoff(1_500L, 1.0))
        // A cap is a cap: the spread is on the wait, never folded back into the
        // curve, so it cannot compound into the growth it decorates.
        assertEquals(8_000L, jitteredBackoff(10_000L, 0.0))
        assertEquals(12_000L, jitteredBackoff(10_000L, 1.0))
    }

    @Test
    fun theJitterNeverWaitsNothing() {
        // A wait of nothing is a loop that spins the phone's radio; the smallest
        // step the curve can hold is still a dial, not a busy turn.
        assertTrue(jitteredBackoff(1L, 0.0) >= 1L)
        assertTrue(jitteredBackoff(0L, 0.0) >= 1L)
    }

    @Test
    fun fiveImmediateDropsWaitLongerEachTime() {
        // The engine takes the upgrade, says ready, and lets go a second later —
        // five times. Today's code reset the curve on every ready, so this is the
        // bug: the phone should not still be dialling 1.5s after the last try.
        val waits = mutableListOf<Long>()
        var backoff = RETRY_MS_FOR_TEST
        repeat(5) {
            val (wait, next) = redialWaits(1_000L, backoff)
            waits += wait
            backoff = next
        }
        assertEquals(listOf(1_500L, 3_000L, 6_000L, 10_000L, 10_000L), waits)
        for (i in 1 until waits.size) {
            assertTrue("drop ${i + 1} must not wait less than the one before", waits[i] >= waits[i - 1])
        }
        assertTrue(
            "a refusal has to outwait its own first retry",
            waits.last() > RETRY_MS_FOR_TEST,
        )
    }

    @Test
    fun aSessionThatHeldIsRedialledFastAgain() {
        // Something blinked — the app was backgrounded, the tunnel restarted. The
        // engine plainly wanted this phone, and the next dial should not pay for
        // a curve grown while the shell was printing.
        val (wait, next) = redialWaits(11_000L, 10_000L)
        assertEquals(1_500L, wait)
        assertEquals(3_000L, next)
    }

    @Test
    fun aDropJustUnderTheSessionBoundaryIsStillACurve() {
        val (wait, next) = redialWaits(9_999L, 3_000L)
        assertEquals(3_000L, wait)
        assertEquals(6_000L, next)
    }

    private companion object {
        /** What `dialLink` starts its curve at. */
        const val RETRY_MS_FOR_TEST = 1_500L
    }
}
