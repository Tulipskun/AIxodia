package com.tulipskun.aixodia.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reading a retry makes of the address the daemon announced.
 *
 * The Named Tunnel hostname is stable. Discovery reads the configured endpoint
 * from KV; an absent endpoint must never replace the address already in use.
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
     * A missing endpoint must not replace a URL already in use.
     */
    @Test
    fun anUnavailableEndpointIsNotAdopted() {
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
