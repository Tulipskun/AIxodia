package com.tulipskun.aixodia.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Discovery replaces the daemon address on its own, so the only decision worth
 * being sure about is when *not* to.
 *
 * The Named Tunnel URL is stable across engine restarts. Discovery adopts a
 * configured endpoint without needing a persisted liveness timestamp.
 */
class DaemonDiscoveryTest {

    private fun node(url: String, online: Boolean, version: String = "abc1234") =
        NodeInfo(tunnelUrl = url, version = version, online = online)

    @Test
    fun `a configured endpoint at a new url is adopted`() {
        val action = DaemonDiscovery.decide(
            currentUrl = "https://old.example.com",
            node = node("https://aixodia.dpdns.org", online = true),
        )
        assertEquals(Discovery.Adopt("https://aixodia.dpdns.org"), action)
    }

    @Test
    fun `the url already in use is not rewritten`() {
        // Writing it again would reset the socket's backoff on every poll and
        // make the connection flap for no reason.
        val action = DaemonDiscovery.decide(
            currentUrl = "https://aixodia.dpdns.org",
            node = node("https://aixodia.dpdns.org", online = true),
        )
        assertEquals(Discovery.Keep, action)
    }

    @Test
    fun `a trailing slash is the same address`() {
        val action = DaemonDiscovery.decide(
            currentUrl = "https://aixodia.dpdns.org",
            node = node("https://aixodia.dpdns.org/", online = true),
        )
        assertEquals(Discovery.Keep, action)
    }

    @Test
    fun `a missing live connection does not make the stored endpoint disappear`() {
        // Online state comes from the actual socket; KV only stores configuration.
        val action = DaemonDiscovery.decide(
            currentUrl = "https://old.example.com",
            node = node("https://dead.example.com", online = false),
        )
        assertEquals(Discovery.Wait, action)
    }

    @Test
    fun `an empty row is waited on, not treated as an address`() {
        assertEquals(Discovery.Wait, DaemonDiscovery.decide("https://old.example.com", null))
        assertEquals(
            Discovery.Wait,
            DaemonDiscovery.decide("https://old.example.com", node("", online = false)),
        )
    }

    @Test
    fun `a blank url is not an address even when the endpoint is marked available`() {
        val action = DaemonDiscovery.decide(
            currentUrl = "https://old.example.com",
            node = NodeInfo(tunnelUrl = "   ", version = "abc", online = true),
        )
        assertEquals(Discovery.Wait, action)
    }

    @Test
    fun `discovery keeps the address it has when KV has no endpoint`() {
        var stored = "https://old.example.com"
        val actions = listOf(
            DaemonDiscovery.decide(stored, null),
            DaemonDiscovery.decide(stored, node("https://dead.example.com", online = false)),
            DaemonDiscovery.decide(stored, node("", online = false)),
        )
        for (action in actions) {
            if (action is Discovery.Adopt) stored = action.url
        }
        assertEquals("https://old.example.com", stored)
        assertTrue(actions.none { it is Discovery.Adopt })
    }
}
