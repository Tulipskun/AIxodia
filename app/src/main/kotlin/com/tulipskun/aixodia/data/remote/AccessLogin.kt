package com.tulipskun.aixodia.data.remote

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.tulipskun.aixodia.ConnConfig
import com.tulipskun.aixodia.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Cloudflare Access sign-in for the Android app.
 *
 * Sign-in happens in Cloudflare, not in this app: Access fronts the engine
 * hostname, shows the GitHub / one-time-PIN login, and stores a
 * `CF_Authorization` cookie for the engine's origin. This class drives that
 * login in an off-screen WebView, then keeps the resulting cookie and presents
 * it on every engine request.
 *
 * Why a WebView and not a browser: the app must end up holding the cookie
 * itself. A Custom Tab would authenticate the user's browser and leave the app
 * without credentials, and shipping a Cloudflare service token in a publicly
 * released APK would hand anyone who downloads it full control of the engine.
 *
 * There is no client secret, no PKCE verifier and no GitHub token here: the
 * only thing stored is the Access cookie, encrypted with the Android Keystore.
 */
object AccessLogin {
    /** Access sets this cookie for the engine's origin once the login succeeds. */
    private const val SESSION_COOKIE = "CF_Authorization"
    private const val PREFS = "aixodia_access_session"
    private const val KEY_COOKIE = "cookie"
    private const val KEY_ORIGIN = "origin"

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /** The cookie header value remembered for [origin], or empty when signed out. */
    fun cookie(context: Context, origin: String): String {
        val store = prefs(context)
        if (store.getString(KEY_ORIGIN, null) != origin.trimEnd('/')) return ""
        return store.getString(KEY_COOKIE, "").orEmpty()
    }

    fun save(context: Context, origin: String, cookie: String) {
        prefs(context).edit()
            .putString(KEY_ORIGIN, origin.trimEnd('/'))
            .putString(KEY_COOKIE, cookie)
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_COOKIE).remove(KEY_ORIGIN).apply()
    }

    /**
     * Run the Access login for [origin] and return the resulting cookie header.
     *
     * The WebView is created with a short-lived Activity context and is expected
     * to be attached to a dialog or used headless by the caller. It is destroyed
     * before returning so no authenticated page is left in memory.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun signIn(context: Context, origin: String): String {
        val base = origin.trim().trimEnd('/')
        require(base.startsWith("https://") || base.startsWith("http://")) {
            "Engine address is invalid"
        }
        CookieManager.getInstance().setAcceptCookie(true)

        return suspendCancellableCoroutine { continuation ->
            var settled = false
            val view = WebView(context)
            fun finish(result: Result<String>) {
                if (settled) return
                settled = true
                view.stopLoading()
                view.destroy()
                result.fold(continuation::resume, continuation::resumeWithException)
            }

            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    val cookie = CookieManager.getInstance().getCookie(base).orEmpty()
                    if (cookie.contains(SESSION_COOKIE)) {
                        save(this@AccessLogin.run { context }, base, cookie)
                        finish(Result.success(cookie))
                    }
                }
            }
            continuation.invokeOnCancellation { finish(Result.failure(CancellationException())) }
            try {
                view.loadUrl("$base/cdn-cgi/access/login")
            } catch (error: Throwable) {
                finish(Result.failure(error))
            }
        }
    }

    /** Forget the local Access cookie. The session at Cloudflare is left alone. */
    suspend fun signOut(context: Context, origin: String) = withContext(Dispatchers.IO) {
        clear(context)
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }
}

/**
 * Supplies the engine credential for a request.
 *
 * The engine no longer issues tokens, so this object's only job is to hand back
 * the Access cookie header. It keeps the small surface the rest of the app
 * already calls (`get`, `refresh`, `reset`) so transports did not have to change.
 */
class ClientToken(
    private val cookieProvider: (ConnConfig) -> String = { "" },
) {
    /**
     * Returns the `Cookie:` header value to present. When Cloudflare Access
     * rejects the stored cookie the call fails and the UI asks for sign-in.
     */
    suspend fun get(config: ConnConfig, settings: SettingsStore): String {
        val fixed = fixedValue
        if (fixed != null) return fixed
        val cookie = cookieProvider(config).ifBlank { config.accessCookie }
        require(cookie.isNotBlank()) { "Sign in to AIxodia to continue" }
        return cookie
    }

    /** Legacy pass-through overload retained for isolated transport tests. */
    suspend fun get(sessionToken: String, daemonUrl: String): String {
        val fixed = fixedValue
        if (fixed != null) return fixed
        require(sessionToken.isNotBlank()) { "Sign in to AIxodia first" }
        return sessionToken
    }

    /** No refresh exists: Cloudflare owns the session, so this is a no-op. */
    suspend fun refresh(refreshToken: String, daemonUrl: String): EngineSession =
        throw IllegalStateException("The session is managed by Cloudflare Access")

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
