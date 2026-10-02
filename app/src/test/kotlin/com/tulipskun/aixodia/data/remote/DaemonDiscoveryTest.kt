package com.tulipskun.aixodia.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Discovery replaces the daemon address on its own, so the only decision worth
 * being sure about is when *not* to.
 *
 * The URL is random per kernel boot and the heartbeat is 30 seconds, so the row
 * spends real time naming a daemon that has already stopped. Adopting that URL
 * would take a working chat offline in exchange for a dead one — and the app
 * would then sit retrying an address nothing answers.
 */
class DaemonDiscoveryTest {

    private fun node(url: String, online: Boolean, ageS: Long = 5, version: String = "abc1234") =
        NodeInfo(tunnelUrl = url, version = version, online = online, ageS = ageS)

    @Test
    fun `a live daemon at a new url is adopted`() {
        val action = DaemonDiscovery.decide(
            currentUrl = "https://old.trycloudflare.com",
            node = node("https://new.trycloudflare.com", online = true),
        )
        assertEquals(Discovery.Adopt("https://new.trycloudflare.com"), action)
    }

    @Test
    fun `the url already in use is not rewritten`() {
        // Writing it again would reset the socket's backoff on every poll and
        // make the connection flap for no reason.
        val action = DaemonDiscovery.decide(
            currentUrl = "https://same.trycloudflare.com",
            node = node("https://same.trycloudflare.com", online = true),
        )
        assertEquals(Discovery.Keep, action)
    }

    @Test
    fun `a trailing slash is the same address`() {
        val action = DaemonDiscovery.decide(
            currentUrl = "https://same.trycloudflare.com",
            node = node("https://same.trycloudflare.com/", online = true),
        )
        assertEquals(Discovery.Keep, action)
    }

    @Test
    fun `a stale heartbeat is never adopted`() {
        // The kernel hit its cap and stopped. Its URL answers nothing, and the
        // address already in hand may still be a running daemon.
        val action = DaemonDiscovery.decide(
            currentUrl = "https://old.trycloudflare.com",
            node = node("https://dead.trycloudflare.com", online = false, ageS = 400),
        )
        assertEquals(Discovery.Wait, action)
    }

    @Test
    fun `an empty row is waited on, not treated as an address`() {
        assertEquals(Discovery.Wait, DaemonDiscovery.decide("https://old.trycloudflare.com", null))
        assertEquals(
            Discovery.Wait,
            DaemonDiscovery.decide("https://old.trycloudflare.com", node("", online = false)),
        )
    }

    @Test
    fun `a blank url is not an address even when the row says online`() {
        // Guards the case where heartbeat is fresh but the URL was never written.
        val action = DaemonDiscovery.decide(
            currentUrl = "https://old.trycloudflare.com",
            node = NodeInfo(tunnelUrl = "   ", version = "abc", online = true, ageS = 1),
        )
        assertEquals(Discovery.Wait, action)
    }

    @Test
    fun `discovery keeps the address it has when nothing is live`() {
        var stored = "https://old.trycloudflare.com"
        val actions = listOf(
            DaemonDiscovery.decide(stored, null),
            DaemonDiscovery.decide(stored, node("https://dead.trycloudflare.com", online = false)),
            DaemonDiscovery.decide(stored, node("", online = false)),
        )
        for (action in actions) {
            if (action is Discovery.Adopt) stored = action.url
        }
        assertEquals("https://old.trycloudflare.com", stored)
        assertTrue(actions.none { it is Discovery.Adopt })
    }
}
