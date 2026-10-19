package com.tulipskun.aixodia.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The relay and the engine speak two different vocabularies on the same
 * socket: the relay's own frames carry "ctl", the engine's carry "ch". A phone
 * that reads only one of them looks connected while nothing works, so the
 * mapping is pinned here.
 */
class RelayFrameKindTest {

    @Test
    fun theRelaysPresenceFrameIsAControlFrame() {
        // {"ctl":"presence","engine":true} — no "ch" at all.
        assertEquals(RelayFrameKind.CONTROL, relayFrameKind("presence", null))
    }

    @Test
    fun theRelaysSupersedeFrameIsAControlFrame() {
        assertEquals(RelayFrameKind.CONTROL, relayFrameKind("superseded-phone", null))
    }

    @Test
    fun theEnginesFramesCarryTheirChannel() {
        assertEquals(RelayFrameKind.RPC, relayFrameKind(null, "rpc"))
        assertEquals(RelayFrameKind.TERM, relayFrameKind(null, "term"))
    }

    @Test
    fun aFrameWithoutAChannelIsChat() {
        assertEquals(RelayFrameKind.CHAT, relayFrameKind(null, null))
        assertEquals(RelayFrameKind.CHAT, relayFrameKind(null, ""))
        assertEquals(RelayFrameKind.CHAT, relayFrameKind(null, "chat"))
    }
}
