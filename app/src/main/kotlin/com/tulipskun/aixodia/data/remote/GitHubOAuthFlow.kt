package com.tulipskun.aixodia.data.remote

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Native GitHub Authorization Code + PKCE flow.
 *
 * GitHub redirects straight back to this app's registered custom scheme
 * (`aixodia://oauth/callback`), so Android sign-in never depends on the
 * dashboard being deployed or reachable. Only the short-lived code travels
 * through the browser; the PKCE verifier stays in encrypted app storage and is
 * sent to the Engine for the server-side exchange.
 */
object GitHubOAuthFlow {
    val CLIENT_ID: String get() = com.tulipskun.aixodia.BuildConfig.GITHUB_CLIENT_ID
    const val CALLBACK_URI = "aixodia://oauth/callback"
    const val REDIRECT_URI = CALLBACK_URI
    private const val PREFS = "aixodia_oauth_pending"
    private const val KEY_STATE = "state"
    private const val KEY_VERIFIER = "verifier"

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private fun randomToken(bytes: Int = 32): String {
        val raw = ByteArray(bytes).also(SecureRandom()::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    fun start(context: Context) {
        val verifier = randomToken(32)
        val state = "aixodia-android.${randomToken(24)}"
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
        )
        prefs(context).edit().putString(KEY_STATE, state).putString(KEY_VERIFIER, verifier).apply()
        val authorization = Uri.parse("https://github.com/login/oauth/authorize").buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", "read:user")
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
        CustomTabsIntent.Builder().build().launchUrl(context, authorization)
    }

    suspend fun complete(context: Context, callback: Uri, daemonUrl: String): EngineSession {
        val store = prefs(context)
        val expectedState = store.getString(KEY_STATE, null)
        val verifier = store.getString(KEY_VERIFIER, null)
        val returnedState = callback.getQueryParameter("state")
        val code = callback.getQueryParameter("code")
        val error = callback.getQueryParameter("error")
        require(error.isNullOrBlank()) { "GitHub sign-in was cancelled or denied: $error" }
        require(!expectedState.isNullOrBlank() && expectedState == returnedState) {
            "GitHub sign-in state did not match. Start sign-in again."
        }
        require(!verifier.isNullOrBlank() && !code.isNullOrBlank()) {
            "GitHub did not return a complete authorization response."
        }
        val session = ClientToken.shared.exchangeAuthorizationCode(
            code = code,
            codeVerifier = verifier,
            redirectUri = REDIRECT_URI,
            clientId = CLIENT_ID,
            daemonUrl = daemonUrl,
        )
        store.edit().remove(KEY_STATE).remove(KEY_VERIFIER).apply()
        return session
    }
}
