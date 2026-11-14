package com.tulipskun.aixodia.data.remote

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.tulipskun.aixodia.ConnConfig
import com.tulipskun.aixodia.SettingsStore
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Native-app sign-in: the browser does the login, this app gets a PKCE code back.
 *
 * Why the system browser and not a WebView (RFC 8252): an embedded user-agent
 * lets the app read the credentials the user types, which trains people to
 * authenticate in windows that may not be theirs. It also cannot reuse the
 * session the user already has in their browser. A Custom Tab fixes both, and
 * the app still ends up with a credential of its own because Access redirects
 * back to the `aixodia://auth/callback` deep link registered in the manifest.
 *
 * No client secret exists in this app — the APK is published publicly, so this
 * is an OAuth public client. PKCE is what stops another app that intercepts the
 * deep link from redeeming the code: only the verifier this device generated
 * hashes to the challenge the engine recorded before the browser opened.
 */
object AccessLogin {
    /** Deep link Access returns to; must match the manifest's intent-filter. */
    const val REDIRECT_URI = "aixodia://auth/callback"

    /**
     * The Cloudflare Access audience tag for the engine hostname.
     *
     * Published metadata, not a secret: it names which Access application the
     * login belongs to and is visible in the login URL of any browser that
     * reaches the engine. The engine verifies the same value server-side, so a
     * wrong or forged copy here buys nothing.
     */
    private const val DEFAULT_ACCESS_AUD =
        "1e2270863178234a46c4f3e1c91a4dc2558d6b428258e4b63ff365fea607cbbd"

    private const val AUTHORIZE_PATH = "/api/auth/authorize"
    private const val EXCHANGE_PATH = "/api/auth/exchange"

    /** PKCE allows 43..128 characters; 48 random bytes encodes to 64. */
    private const val VERIFIER_BYTES = 48

    /** base64url without padding, as PKCE requires. */
    private fun base64Url(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)

    fun newVerifier(): String {
        val bytes = ByteArray(VERIFIER_BYTES)
        SecureRandom().nextBytes(bytes)
        return base64Url(bytes)
    }

    fun challengeFor(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return base64Url(digest)
    }

    /**
     * The verifier for the attempt currently in flight.
     *
     * It has to outlive the browser round trip, which leaves this process and
     * comes back through a deep link handled by MainActivity — so it cannot
     * live in composable state. It is only ever held in memory: a stolen
     * verifier plus a stolen code is what PKCE exists to prevent, and neither is
     * written to disk.
     */
    @Volatile
    var pendingVerifier: String = ""
        private set

    /** Starts a sign-in attempt and remembers the verifier for the exchange. */
    fun startAttempt(): String = newVerifier().also { pendingVerifier = it }

    /** Drops the in-flight verifier once it has been redeemed. */
    fun clearPending() {
        pendingVerifier = ""
    }

    private val http = OkHttpClient.Builder().build()

    /** Registers the attempt and returns the URL to open in the browser. */
    suspend fun begin(origin: String, verifier: String): String = withContext(Dispatchers.IO) {
        val base = origin.trim().trimEnd('/')
        val body = JSONObject()
            .put("code_challenge", challengeFor(verifier))
            .put("redirect_uri", REDIRECT_URI)
            .toString()

        val request = Request.Builder()
            .url("$base$AUTHORIZE_PATH")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val response = http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Could not start sign-in (${response.code})")
            }
            text
        }

        val code = runCatching { JSONObject(response).optString("code") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: throw IOException("Could not start sign-in: no code returned")

        // The browser lands on the engine's own hostname first, because that is
        // the only redirect Access accepts. Three details are load-bearing, all
        // found by testing against the live hostname:
        //   - the hostname must appear in the path, or Access answers 404;
        //   - `kid` is required, and is the app's audience tag;
        //   - redirect_url must be a *relative* path. A full URL answers 400.
        loginUrlFor(base, code)
    }

    /** The Access login URL; see the notes above for why each part is required. */
    internal fun loginUrlFor(origin: String, code: String): String {
        val base = origin.trim().trimEnd('/')
        val callbackPath = "/api/auth/callback?code=$code"
        return "$base/cdn-cgi/access/login/${hostOf(base)}?kid=${appAudience()}" +
            "&redirect_url=" + java.net.URLEncoder.encode(callbackPath, "UTF-8")
    }

    private fun hostOf(origin: String): String =
        origin.trim().trimEnd('/').removePrefix("https://").removePrefix("http://").substringBefore('/')

    /**
     * The Access application audience tag, which Access calls `kid` on its login
     * URL. It is published, not secret: it identifies which application the
     * login is for. Both builders that point at this hostname use this value.
     */
    private fun appAudience(): String = DEFAULT_ACCESS_AUD

    /** Trades the deep-link code for a session token. */
    suspend fun exchange(origin: String, code: String, verifier: String): Session =
        withContext(Dispatchers.IO) {
            val base = origin.trim().trimEnd('/')
            val body = JSONObject()
                .put("code", code)
                .put("code_verifier", verifier)
                .put("redirect_uri", REDIRECT_URI)
                .toString()

            val request = Request.Builder()
                .url("$base$EXCHANGE_PATH")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException(exchangeError(response.code, text))
                }
                val obj = runCatching { JSONObject(text) }.getOrNull()
                    ?: throw IOException("Sign-in returned an unexpected response")
                Session(
                    token = obj.optString("token"),
                    subject = obj.optString("subject"),
                    expiresAt = obj.optLong("expires_at", 0L),
                )
            }
        }

    private fun exchangeError(status: Int, text: String): String {
        val code = runCatching { JSONObject(text).optString("code") }.getOrNull()
        return when (code) {
            "PKCE_MISMATCH" -> "Sign-in could not be verified. Try again."
            "UNKNOWN_CODE" -> "This sign-in link has expired. Try again."
            "NO_SUBJECT" -> "Sign-in did not finish in the browser. Try again."
            "REDIRECT_MISMATCH" -> "Sign-in was redirected unexpectedly. Try again."
            else -> "Sign-in failed ($status)"
        }
    }

    /** Opens [url] in a Custom Tab, the browser surface RFC 8252 asks for. */
    fun openBrowser(context: Context, url: String) {
        val intent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.launchUrl(context, Uri.parse(url))
    }

    /** Pulls the one-time code out of the `aixodia://auth/callback` deep link. */
    fun codeFromDeepLink(uri: Uri): String? =
        if (uri.scheme == "aixodia" && uri.host == "auth") uri.getQueryParameter("code") else null

    data class Session(val token: String, val subject: String, val expiresAt: Long)
}

/**
 * Supplies the engine credential for a request.
 *
 * The engine issues the session token now, so this reads it from
 * [SettingsStore] and presents it as a bearer. Kept as a class with the same
 * small surface the transports already call.
 */
class ClientToken(
    private val tokenProvider: (ConnConfig) -> String = { "" },
) {
    suspend fun get(config: ConnConfig, settings: SettingsStore): String {
        val fixed = fixedValue
        if (fixed != null) return fixed
        val token = tokenProvider(config).ifBlank { config.sessionToken }
        require(token.isNotBlank()) { "Sign in to AIxodia to continue" }
        return token
    }

    /** Legacy pass-through overload retained for isolated transport tests. */
    suspend fun get(sessionToken: String, daemonUrl: String): String {
        val fixed = fixedValue
        if (fixed != null) return fixed
        require(sessionToken.isNotBlank()) { "Sign in to AIxodia first" }
        return sessionToken
    }

    /** Sessions are minted by the engine; re-run the browser hand-off instead. */
    suspend fun refresh(refreshToken: String, daemonUrl: String): EngineSession =
        throw IllegalStateException("Sign in again to renew the session")

    fun reset() = Unit

    companion object {
        val shared: ClientToken = ClientToken()
        val passThrough: ClientToken = ClientToken()
        fun fixed(value: String): ClientToken = ClientToken().also { it.fixedValue = value }
    }

    private var fixedValue: String? = null
}

/** Kept for source compatibility with callers that stored engine sessions. */
data class EngineSession(
    val token: String,
    val expiresAt: Long,
    val refreshToken: String,
    val refreshExpiresAt: Long,
    val user: String,
)
