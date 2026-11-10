package com.tulipskun.aixodia.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** A first-party Engine session. The GitHub credential is never persisted. */
data class EngineSession(
    val token: String,
    val expiresAt: Long,
    val user: String,
)

/** Exchanges a one-time GitHub OAuth credential for an AIxodia session. */
class ClientToken(
    private val client: OkHttpClient = defaultClient(),
) {
    /** GitHub credentials are used only during this explicit sign-in exchange. */
    suspend fun exchange(githubToken: String, daemonUrl: String): EngineSession =
        withContext(Dispatchers.IO) {
            val base = daemonUrl.trim().trimEnd('/')
            require(githubToken.isNotBlank()) { "GitHub sign-in did not return an access token" }
            require(base.startsWith("https://") || base.startsWith("http://")) {
                "Engine address is invalid"
            }
            val body = JSONObject().put("github_token", githubToken).toString()
            val request = Request.Builder()
                .url("$base/v1/auth/token")
                .header("Accept", "application/json")
                .post(body.toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(raw) }.getOrNull()
                if (!response.isSuccessful) {
                    val message = json?.optString("error")?.takeIf { it.isNotBlank() }
                        ?: "Engine rejected GitHub sign-in (HTTP ${response.code})"
                    throw IllegalStateException(message)
                }
                val token = json?.optString("token").orEmpty()
                val expiresAt = json?.optLong("expires_at") ?: 0L
                if (token.isBlank() || expiresAt <= System.currentTimeMillis() / 1000) {
                    throw IllegalStateException("Engine returned an invalid or expired session")
                }
                EngineSession(token, expiresAt, json?.optString("user").orEmpty())
            }
        }

    /** API transports receive an already-issued Engine session, never GitHub credentials. */
    suspend fun get(sessionToken: String, daemonUrl: String): String {
        val fixed = fixedValue
        if (fixed != null) return fixed
        require(sessionToken.isNotBlank()) { "Sign in with GitHub first" }
        return sessionToken
    }

    fun reset() = Unit

    companion object {
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
