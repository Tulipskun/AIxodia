package com.tulipskun.aixodia.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reading a retry makes of the address the daemon announced.
 *
 * A quick tunnel's host dies with the engine that opened it, so the phone's
 * stored URL is a name that resolves to nothing until it reads the new one.
 * What it reads is not always worth dialling: the row is written by whichever
 * process last had one, and adopting whatever is in it would let a stale or
 * half-written announcement replace an address the phone can still reach.
 */
class RunSocketAddressTest {

    private val held = "https://old-host.example.trycloudflare.com"

    private fun node(url: String, online: Boolean) = NodeInfo(tunnelUrl = url, online = online)

    @Test
    fun aDaemonThatMovedIsFollowed() {
        assertEquals(
            "https://new-host.example.trycloudflare.com",
            pickDaemonAddress(held, node("https://new-host.example.trycloudflare.com", true)),
        )
    }

    @Test
    fun aTrailingSlashIsNotADifferentDaemon() {
        assertNull(pickDaemonAddress(held, node("$held/", true)))
        assertNull(pickDaemonAddress("$held/", node(held, true)))
    }

    /**
     * The half of the complaint the fix must not overshoot: a row whose writer
     * stopped beating describes a tunnel that is gone. Trading a live address
     * for that would turn a working app into a broken one.
     */
    @Test
    fun anAnnouncementWithNoHeartbeatBehindItIsRefused() {
        assertNull(pickDaemonAddress(held, node("https://new-host.example.trycloudflare.com", false)))
    }

    @Test
    fun nothingAnnouncedIsNothingToGoWith() {
        assertNull(pickDaemonAddress(held, null))
        assertNull(pickDaemonAddress(held, node("", true)))
        assertNull(pickDaemonAddress(held, node("   ", true)))
    }

    /** The first dial of all: no address in hand yet, so any live one is an adoption. */
    @Test
    fun anEmptyHandTakesWhatTheDaemonSays() {
        assertEquals("https://new-host.example.trycloudflare.com", pickDaemonAddress("", node("https://new-host.example.trycloudflare.com", true)))
    }
}
