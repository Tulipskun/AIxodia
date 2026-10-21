package com.tulipskun.aixodia.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A reconnect that always waits one second hammers a server that accepts and
 * drops the socket immediately; one that doubles forever leaves a phone asleep
 * for half a minute after a single blip. The policy is pinned here: only a
 * socket that stayed up resets the delay, and the doubling is capped.
 */
class RelayBackoffTest {

    @Test
    fun aFirstFailureStartsFromTheBaseDelay() {
        // Current is the base, nothing was up: the next wait is one step up.
        assertEquals(2_000L, nextBackoffMs(RELAY_BASE_BACKOFF_MS, upMs = 0))
    }

    @Test
    fun repeatedInstantFailuresDoubleUntilTheCeiling() {
        assertEquals(2_000L, nextBackoffMs(1_000L, 0))
        assertEquals(4_000L, nextBackoffMs(2_000L, 0))
        assertEquals(8_000L, nextBackoffMs(4_000L, 0))
        assertEquals(8_000L, nextBackoffMs(8_000L, 0))
    }

    @Test
    fun aSocketThatStayedUpResetsTheDelay() {
        assertEquals(1_000L, nextBackoffMs(8_000L, RELAY_STABLE_MS))
        assertEquals(1_000L, nextBackoffMs(8_000L, 60_000L))
    }

    @Test
    fun oneJustUnderTheStableWindowStillBacksOff() {
        // The socket survived 9.9s — not proof the link is healthy.
        assertEquals(16_000L.coerceAtMost(RELAY_MAX_BACKOFF_MS), nextBackoffMs(8_000L, 9_900L))
    }

    @Test
    fun aZeroCurrentNeverProducesNoWait() {
        // Sane even if a caller forgets to seed the delay.
        assertEquals(2_000L, nextBackoffMs(0, 0))
    }
}
