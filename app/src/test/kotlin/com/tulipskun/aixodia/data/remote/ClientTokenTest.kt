package com.tulipskun.aixodia.data.remote

import com.tulipskun.aixodia.ConnConfig
import com.tulipskun.aixodia.SettingsStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ClientTokenTest {
    private fun sessionJson(token: String = "APP_SESSION", user: String = "Tulipskun") =
        """{"token":"$token","expires_at":9999999999,"refresh_token":"LONG_REFRESH","refresh_expires_at":19999999999,"user":"$user","scope":"client"}"""

    @Test
    fun `exchanges GitHub credential for an Engine session and refresh credential`() {
        val d = FakeDaemon().apply { respond("/v1/auth/token", 200, sessionJson()) }
        try {
            val session = runBlocking { ClientToken().exchange("TEMP_GITHUB_TOKEN", d.base) }
            assertEquals("APP_SESSION", session.token)
            assertEquals("LONG_REFRESH", session.refreshToken)
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
    fun `refreshes an expired access session without contacting GitHub`() {
        val d = FakeDaemon().apply { respond("/v1/auth/refresh", 200, sessionJson("RENEWED_SESSION")) }
        try {
            val session = runBlocking { ClientToken().refresh("LONG_REFRESH", d.base) }
            assertEquals("RENEWED_SESSION", session.token)
            assertEquals("LONG_REFRESH", session.refreshToken)
            val sent = JSONObject(d.lastBody("/v1/auth/refresh"))
            assertEquals("LONG_REFRESH", sent.getString("refresh_token"))
            assertFalse(sent.has("github_token"))
            assertEquals(1, d.hits("/v1/auth/refresh"))
        } finally {
            d.close()
        }
    }

    @Test
    fun `automatically refreshes an expired access token before API use`() {
        val d = FakeDaemon().apply {
            respond("/v1/auth/refresh", 200, sessionJson("RENEWED_SESSION"))
        }
        try {
            val settings: SettingsStore = mock()
            val config = ConnConfig(
                endpoint = d.base,
                daemonUrl = d.base,
                token = "",
                sessionId = "session",
                sessionToken = "",
                sessionExpiresAt = 0L,
                refreshToken = "LONG_REFRESH",
                refreshExpiresAt = 9_999_999_999L,
            )
            runBlocking {
                whenever(settings.current()).thenReturn(config)
                assertEquals("RENEWED_SESSION", ClientToken().get(config, settings))
            }
            assertEquals(1, d.hits("/v1/auth/refresh"))
            assertEquals("LONG_REFRESH", JSONObject(d.lastBody("/v1/auth/refresh")).getString("refresh_token"))
        } finally {
            d.close()
        }
    }

    @Test
    fun `rejects a response without a long-lived refresh credential`() {
        val d = FakeDaemon().apply {
            respond("/v1/auth/token", 200, """{"token":"APP_SESSION","expires_at":9999999999,"user":"Tulipskun"}""")
        }
        try {
            assertThrows(IllegalStateException::class.java) {
                runBlocking { ClientToken().exchange("TEMP_GITHUB_TOKEN", d.base) }
            }
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
