package com.tulipskun.aixodia.data.remote

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The short-lived token the engine mints from the GitHub OAuth token is the
 * only credential the app sends to the engine now. These cases pin the
 * contract: it exchanges once, reuses the cached value, refreshes before it
 * lapses, sends the credential as `github_token` (never `master_token`), and
 * falls back to the raw credential when the engine predates the endpoint, so
 * an old engine keeps working instead of leaving the app stranded.
 */
class ClientTokenTest {
    private fun daemon(body: String): FakeDaemon =
        FakeDaemon().also { it.respond("/v1/auth/token", 200, body) }

    @Test
    fun `exchanges the GitHub token for a short-lived token`() {
        val d = daemon("""{"token":"SHORT","expires_at":1000000000}""")
        try {
            val ct = ClientToken()
            assertEquals("SHORT", runBlocking { ct.get("GH_TOKEN", d.base) })
            assertEquals(1, d.hits("/v1/auth/token"))
            val sent = JSONObject(d.lastBody("/v1/auth/token"))
            assertEquals("GH_TOKEN", sent.getString("github_token"))
            assertEquals(
                "the Cloudflare token must never be sent",
                false,
                sent.has("master_token"),
            )
        } finally {
            d.close()
        }
    }

    @Test
    fun `reuses the cached token until it nears expiry`() {
        var clock = 100L
        val d = daemon("""{"token":"A","expires_at":${clock + 900L}}""")
        try {
            val ct = ClientToken(clock = { clock })
            assertEquals("A", runBlocking { ct.get("M", d.base) })
            clock = 100L + 800L // 100s remain, well past the 60s skew
            assertEquals("A", runBlocking { ct.get("M", d.base) })
            assertEquals("no second exchange while still valid", 1, d.hits("/v1/auth/token"))
        } finally {
            d.close()
        }
    }

    @Test
    fun `refreshes once the token enters its skew window`() {
        var clock = 100L
        val d = daemon("""{"token":"A","expires_at":${clock + 900L}}""")
        try {
            val ct = ClientToken(clock = { clock })
            assertEquals("A", runBlocking { ct.get("M", d.base) })
            clock = 100L + 870L // within 60s of expiry -> refresh
            assertEquals("A", runBlocking { ct.get("M", d.base) })
            assertEquals("a fresh exchange fires before lapse", 2, d.hits("/v1/auth/token"))
        } finally {
            d.close()
        }
    }

    @Test
    fun `falls back to the raw credential when the endpoint is absent`() {
        val d = FakeDaemon().apply { respond("/v1/auth/token", 404) }
        try {
            val ct = ClientToken()
            assertEquals("MASTER", runBlocking { ct.get("MASTER", d.base) })
        } finally {
            d.close()
        }
    }

    @Test
    fun `falls back to the raw credential when the exchange is refused`() {
        val d = FakeDaemon().apply { respond("/v1/auth/token", 401) }
        try {
            val ct = ClientToken()
            assertEquals("MASTER", runBlocking { ct.get("MASTER", d.base) })
        } finally {
            d.close()
        }
    }

    @Test
    fun `passThrough never touches the network`() {
        val d = daemon("""{"token":"X","expires_at":1}""")
        try {
            assertEquals("MASTER", runBlocking { ClientToken.passThrough.get("MASTER", d.base) })
            assertEquals(0, d.hits("/v1/auth/token"))
        } finally {
            d.close()
        }
    }

    @Test
    fun `fixed always returns the forced value`() {
        val d = daemon("""{"token":"X","expires_at":1}""")
        try {
            assertEquals("FORCED", runBlocking { ClientToken.fixed("FORCED").get("MASTER", d.base) })
            assertEquals(0, d.hits("/v1/auth/token"))
        } finally {
            d.close()
        }
    }
}
