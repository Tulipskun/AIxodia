package com.tulipskun.aixodia.data.remote

import com.tulipskun.aixodia.ConnConfig
import com.tulipskun.aixodia.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Engine-issued credentials. Only these credentials are persisted by Android. */
data class EngineSession(
    val token: String,
    val expiresAt: Long,
    val refreshToken: String,
    val refreshExpiresAt: Long,
    val user: String,
)

/** GitHub Device Flow exchange and silent refresh for the Engine API session. */
class ClientToken(
    private val client: OkHttpClient = defaultClient(),
) {
    private val refreshMutex = Mutex()

    suspend fun exchange(githubToken: String, daemonUrl: String): EngineSession =
        requestSession(daemonUrl, "/v1/auth/token", "github_token", githubToken)

    /** Exchange the short-lived GitHub authorization code through the Engine.
     * The GitHub OAuth client secret never enters the Android application. */
    suspend fun exchangeAuthorizationCode(
        code: String,
        codeVerifier: String,
        redirectUri: String,
        clientId: String,
        daemonUrl: String,
    ): EngineSession = withContext(Dispatchers.IO) {
        val base = daemonUrl.trim().trimEnd('/')
        require(base.startsWith("https://") || base.startsWith("http://")) {
            "Engine address is invalid"
        }
        val body = JSONObject()
            .put("code", code)
            .put("code_verifier", codeVerifier)
            .put("redirect_uri", redirectUri)
            .put("client_id", clientId)
            .toString()
        val request = Request.Builder()
            .url("$base/v1/auth/github/exchange")
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrNull()
            if (!response.isSuccessful) {
                val message = json?.optString("error")?.takeIf { it.isNotBlank() }
                    ?: "Engine authentication failed (HTTP ${response.code})"
                throw IllegalStateException(message)
            }
            val token = json?.optString("token").orEmpty()
            val expiresAt = json?.optLong("expires_at") ?: 0L
            val nextRefresh = json?.optString("refresh_token").orEmpty()
            val refreshExpiresAt = json?.optLong("refresh_expires_at") ?: 0L
            val now = System.currentTimeMillis() / 1000
            if (token.isBlank() || expiresAt <= now || nextRefresh.isBlank() || refreshExpiresAt <= now) {
                throw IllegalStateException("Engine returned incomplete or expired session credentials")
            }
            EngineSession(token, expiresAt, nextRefresh, refreshExpiresAt, json?.optString("user").orEmpty())
        }
    }

    suspend fun refresh(refreshToken: String, daemonUrl: String): EngineSession =
        requestSession(daemonUrl, "/v1/auth/refresh", "refresh_token", refreshToken)

    private suspend fun requestSession(
        daemonUrl: String,
        path: String,
        credentialName: String,
        credential: String,
    ): EngineSession = withContext(Dispatchers.IO) {
        val base = daemonUrl.trim().trimEnd('/')
        require(credential.isNotBlank()) { "Authentication credential is missing" }
        require(base.startsWith("https://") || base.startsWith("http://")) {
            "Engine address is invalid"
        }
        val body = JSONObject().put(credentialName, credential).toString()
        val request = Request.Builder()
            .url("$base$path")
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrNull()
            if (!response.isSuccessful) {
                val message = json?.optString("error")?.takeIf { it.isNotBlank() }
                    ?: "Engine authentication failed (HTTP ${response.code})"
                throw IllegalStateException(message)
            }
            val token = json?.optString("token").orEmpty()
            val expiresAt = json?.optLong("expires_at") ?: 0L
            val nextRefresh = json?.optString("refresh_token").orEmpty()
            val refreshExpiresAt = json?.optLong("refresh_expires_at") ?: 0L
            val now = System.currentTimeMillis() / 1000
            if (token.isBlank() || expiresAt <= now || nextRefresh.isBlank() || refreshExpiresAt <= now) {
                throw IllegalStateException("Engine returned incomplete or expired session credentials")
            }
            EngineSession(
                token = token,
                expiresAt = expiresAt,
                refreshToken = nextRefresh,
                refreshExpiresAt = refreshExpiresAt,
                user = json?.optString("user").orEmpty(),
            )
        }
    }

    /** Return a usable access token, silently refreshing it when near expiry. */
    suspend fun get(config: ConnConfig, settings: SettingsStore): String {
        val fixed = fixedValue
        if (fixed != null) return fixed
        if (this === passThrough) return get(config.sessionToken, config.daemonUrl)
        val now = System.currentTimeMillis() / 1000
        if (config.sessionToken.isNotBlank() && (
                config.refreshToken.isBlank() || config.sessionExpiresAt > now + REFRESH_SKEW_SECONDS
            )
        ) {
            return config.sessionToken
        }
        require(config.refreshToken.isNotBlank() && config.refreshExpiresAt > now) {
            "Sign in with GitHub again to continue"
        }
        return refreshMutex.withLock {
            // Another request may have refreshed the credentials while this one
            // waited. Always re-read the latest atomic DataStore snapshot.
            val latest = settings.current()
            val currentTime = System.currentTimeMillis() / 1000
            if (latest.sessionToken.isNotBlank() && (
                    latest.refreshToken.isBlank() || latest.sessionExpiresAt > currentTime + REFRESH_SKEW_SECONDS
                )
            ) {
                return@withLock latest.sessionToken
            }
            require(latest.refreshToken.isNotBlank() && latest.refreshExpiresAt > currentTime) {
                "Sign in with GitHub again to continue"
            }
            val renewed = refresh(latest.refreshToken, latest.daemonUrl)
            settings.saveSessionCredentials(
                renewed.token,
                renewed.expiresAt,
                renewed.refreshToken,
                renewed.refreshExpiresAt,
            )
            renewed.token
        }
    }

    /** Legacy pass-through overload retained for isolated transport tests. */
    suspend fun get(sessionToken: String, daemonUrl: String): String {
        val fixed = fixedValue
        if (fixed != null) return fixed
        require(sessionToken.isNotBlank()) { "Sign in with GitHub first" }
        return sessionToken
    }

    fun reset() = Unit

    companion object {
        private const val REFRESH_SKEW_SECONDS = 120L
        val shared: ClientToken = ClientToken()
        val passThrough: ClientToken = ClientToken()
        fun fixed(value: String): ClientToken = ClientToken().also { it.fixedValue = value }
        private val JSON = "application/json".toMediaType()
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private var fixedValue: String? = null
}
