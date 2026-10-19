package com.tulipskun.aixodia.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The relay address carries the room in its path, but the engine's mux serves
 * `/v1/...` and `/api/...` at its root. This mapping is what keeps every REST
 * call in HistoryApi working unchanged, so it is checked directly.
 */
class RelayPathTest {

    @Test
    fun stripsTheRoomPrefix() {
        assertEquals(
            "/v1/sessions",
            relayPath("https://relay.example/room/aixodia/v1/sessions", "https://relay.example/room/aixodia"),
        )
    }

    @Test
    fun keepsTheQuery() {
        assertEquals(
            "/api/logs?limit=50",
            relayPath("https://relay.example/room/aixodia/api/logs?limit=50", "https://relay.example/room/aixodia/"),
        )
    }

    @Test
    fun aRootDaemonNeedsNoPrefix() {
        assertEquals(
            "/v1/models",
            relayPath("https://host/v1/models", "https://host"),
        )
    }

    @Test
    fun anotherHostIsNotTheEngine() {
        assertNull(relayPath("https://api.cloudflare.com/v1/x", "https://relay.example/room/aixodia"))
    }

    @Test
    fun aSiblingPathIsNotTheEngine() {
        assertNull(relayPath("https://relay.example/other/v1/sessions", "https://relay.example/room/aixodia"))
    }
}
