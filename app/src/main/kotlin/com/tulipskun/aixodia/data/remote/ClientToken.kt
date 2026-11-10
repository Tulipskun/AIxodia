package com.tulipskun.aixodia.data.remote

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Exchanges the GitHub OAuth access token (from the Device Flow sign-in) for
 * the short-lived opaque token the engine mints at `POST /v1/auth/token`.
 *
 * The exchange happens once, at startup, and again only when the cached token
 * is within [refreshSkewSec] of its `expires_at`. Everything is kept in memory
 * only — nothing is written to SharedPreferences, Room or disk — and guarded by
 * a [Mutex] so concurrent callers share one exchange instead of stampeding the
 * engine. A [ClientToken] is meant to be a single app-wide instance
 * ([ClientToken.shared]) so every transport reuses the same cached token.
 *
 * Engines without `/v1/auth/token` (404) or refusing the exchange (401) leave
 * every call falling back to the raw credential for a cooldown, so the app
 * keeps talking to the engine exactly as before instead of hanging. The GitHub
 * token is only ever sent to the engine's exchange endpoint — never to
 * Cloudflare, and never persisted.
 */
class ClientToken(
    private val client: OkHttpClient = defaultClient(),
    /** Unix seconds. Injectable so a test can name the moment of expiry. */
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val refreshSkewSec: Long = 60L,
    private val maxAttempts: Int = 3,
    private val retryBaseMs: Long = 1_000L,
    private val noEndpointCooldownSec: Long = 300L,
    private val unavailableCooldownSec: Long = 30L,
    private val exchangeEnabled: Boolean = true,
    private val forced: String? = null,
) {
    private val mutex = Mutex()

    // ---- in-memory only: no persistence anywhere ----
    private var cachedToken: String = ""
    private var expiresAt: Long = 0L
    /** The (daemonUrl|masterToken) pair the cached token belongs to. */
    private var cachedKey: String = ""
    /** Key -> unix second before which another exchange must not be attempted. */
    private val fallbackUntil = HashMap<String, Long>()

    /**
     * The bearer value for an engine request. Returns the short-lived token
     * when the engine supports the exchange, otherwise the master token.
     */
    suspend fun get(githubToken: String, daemonUrl: String): String = mutex.withLock {
        // A fixed value (deterministic wiring tests) or a disabled exchange
        // (direct construction in unit tests) never touches the network.
        if (forced != null) return@withLock forced
        val base = daemonUrl.trim().trimEnd('/')
        if (!exchangeEnabled || githubToken.isBlank() || base.isEmpty()) {
            return@withLock githubToken
        }
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            return@withLock githubToken
        }
        val key = "$base|$githubToken"
        val now = clock()
        // A valid cached token that is not yet near expiry is reused.
        if (cachedKey == key && cachedToken.isNotEmpty() && now < expiresAt - refreshSkewSec) {
            return@withLock cachedToken
        }
        // Honour a previous "this engine cannot exchange" decision for a while.
        if (now < (fallbackUntil[key] ?: 0L)) return@withLock githubToken

        when (val outcome = exchange(base, githubToken, now)) {
            is Outcome.Token -> {
                cachedKey = key
                cachedToken = outcome.value
                expiresAt = outcome.expiresAt
                fallbackUntil.remove(key)
                return@withLock outcome.value
            }
            is Outcome.NoEndpoint -> {
                fallbackUntil[key] = now + noEndpointCooldownSec
                clearCache()
                return@withLock githubToken
            }
            is Outcome.Unavailable -> {
                fallbackUntil[key] = now + unavailableCooldownSec
                clearCache()
                return@withLock githubToken
            }
        }
    }

    /** One exchange, with bounded retries on transient (5xx) answers. */
    private suspend fun exchange(base: String, githubToken: String, now: Long): Outcome {
        val body = JSONObject().put("github_token", githubToken).toString()
        var wait = retryBaseMs
        repeat(maxAttempts) { attempt ->
            val req = Request.Builder()
                .url("$base/v1/auth/token")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody(JSON))
                .build()
            try {
                client.newCall(req).execute().use { r ->
                    val raw = r.body?.string().orEmpty()
                    when {
                        r.isSuccessful -> {
                            val json = runCatching { JSONObject(raw) }.getOrNull()
                            val token = json?.optString("token").orEmpty()
                            if (token.isBlank()) return Outcome.Unavailable
                            val exp = json!!.optLong("expires_at")
                            return Outcome.Token(token, if (exp > 0) exp else now + DEFAULT_TTL_SEC)
                        }
                        // An old engine: no route, or the master token refused.
                        r.code == 404 || r.code == 401 -> return Outcome.NoEndpoint
                        r.code in 500..599 -> Unit // transient: fall through to a retry
                        else -> return Outcome.NoEndpoint
                    }
                }
            } catch (e: IOException) {
                // No response at all (dead tunnel, no route on a mock server).
                // Fall back immediately — a network-level failure must not hold
                // the app up or be retried into a long backoff.
                return Outcome.Unavailable
            } catch (e: Exception) {
                return Outcome.Unavailable
            }
            if (attempt < maxAttempts - 1) {
                delay(jitteredBackoff(wait, Random.nextDouble()))
                wait = (wait * 2).coerceAtMost(8_000L)
            }
        }
        return Outcome.Unavailable
    }

    private fun clearCache() {
        cachedKey = ""
        cachedToken = ""
        expiresAt = 0L
    }

    /** Clears all in-memory state. For test isolation only. */
    fun reset() {
        cachedKey = ""
        cachedToken = ""
        expiresAt = 0L
        fallbackUntil.clear()
    }

    private sealed interface Outcome {
        data class Token(val value: String, val expiresAt: Long) : Outcome
        data object NoEndpoint : Outcome
        data object Unavailable : Outcome
    }

    companion object {
        const val DEFAULT_TTL_SEC = 900L
        val JSON = "application/json".toMediaType()

        /** The one app-wide provider used in production. */
        val shared: ClientToken = ClientToken()

        /**
         * A provider that never exchanges. This is the default for transports
         * that unit tests construct directly, so a test never performs a live
         * token exchange (and its mock server never sees an extra request).
         * Production wires [shared] through `AppContainer` / the screens.
         */
        val passThrough: ClientToken = ClientToken(exchangeEnabled = false)

        /** A provider that always returns [value], for deterministic wiring tests. */
        fun fixed(value: String): ClientToken = ClientToken(forced = value)

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
