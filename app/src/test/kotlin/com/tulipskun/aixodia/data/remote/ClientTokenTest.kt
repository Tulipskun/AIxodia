package com.tulipskun.aixodia.data.remote

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ClientTokenTest {
    @Test
    fun `exchanges GitHub credential for an Engine session`() {
        val d = FakeDaemon().apply {
            respond("/v1/auth/token", 200, """{"token":"APP_SESSION","expires_at":9999999999,"user":"Tulipskun"}""")
        }
        try {
            val session = runBlocking { ClientToken().exchange("TEMP_GITHUB_TOKEN", d.base) }
            assertEquals("APP_SESSION", session.token)
            assertEquals("Tulipskun", session.user)
            val sent = JSONObject(d.lastBody("/v1/auth/token"))
            assertEquals("TEMP_GITHUB_TOKEN", sent.getString("github_token"))
            assertFalse("Cloudflare credentials must never be sent", sent.has("master_token"))
            assertEquals(1, d.hits("/v1/auth/token"))
        } finally {
            d.close()
        }
    }

    @Test
    fun `does not fall back to GitHub credential when Engine route is missing`() {
        val d = FakeDaemon().apply { respond("/v1/auth/token", 404, "{}") }
        try {
            assertThrows(IllegalStateException::class.java) {
                runBlocking { ClientToken().exchange("TEMP_GITHUB_TOKEN", d.base) }
            }
        } finally {
            d.close()
        }
    }

    @Test
    fun `does not accept a rejected GitHub credential`() {
        val d = FakeDaemon().apply {
            respond("/v1/auth/token", 403, """{"error":"GitHub user is not allowed"}""")
        }
        try {
            val error = assertThrows(IllegalStateException::class.java) {
                runBlocking { ClientToken().exchange("TEMP_GITHUB_TOKEN", d.base) }
            }
            assertEquals("GitHub user is not allowed", error.message)
        } finally {
            d.close()
        }
    }

    @Test
    fun `API authorization passes through only the stored Engine session`() {
        assertEquals("APP_SESSION", runBlocking { ClientToken.passThrough.get("APP_SESSION", "https://engine.example") })
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { ClientToken.passThrough.get("", "https://engine.example") }
        }
    }
}
