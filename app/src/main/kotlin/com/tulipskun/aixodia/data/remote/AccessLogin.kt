package com.tulipskun.aixodia.data.remote

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.tulipskun.aixodia.ConnConfig
import com.tulipskun.aixodia.SettingsStore
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    /**
     * The URL the WebView starts on.
     *
     * Not `/cdn-cgi/access/login`: that route answers 404 unless it is handed a
     * kid and redirect_url, which stranded the sign-in on an error page. Any
     * protected path works because Access redirects it to the real login page.
     */
    internal fun loginEntryUrl(origin: String): String =
        "${origin.trim().trimEnd('/')}/healthz"

    /**
     * The cookie lives in [SettingsStore], not in a store of this object's own.
     *
     * It briefly used EncryptedSharedPreferences here while the sign-in gate read
     * `SettingsStore.isSignedInFlow` from DataStore. Nothing ever wrote the value
     * the gate was watching, so a successful login left the user on the sign-in
     * screen. One owner, one key, or the two disagree.
     */
    private fun store(context: Context) = SettingsStore(context)

    /** The cookie header value remembered for [origin], or empty when signed out. */
    suspend fun cookie(context: Context, origin: String): String = withContext(Dispatchers.IO) {
        val stored = store(context).current()
        if (stored.daemonUrl.trimEnd('/') != origin.trimEnd('/')) return@withContext ""
        stored.accessCookie
    }

    suspend fun save(context: Context, origin: String, cookie: String) = withContext(Dispatchers.IO) {
        store(context).saveAccessCookie(cookie)
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        store(context).saveAccessCookie("")
    }

    /**
     * Run the Access login for [origin] and return the resulting cookie header.
     *
     * The WebView is shown in a dialog the caller owns ([attach]), because the
     * user has to see and dismiss Cloudflare's page. It is destroyed before
     * returning so no authenticated page is left in memory.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun signIn(
        context: Context,
        origin: String,
        attach: (WebView) -> Unit = {},
    ): String {
        val base = origin.trim().trimEnd('/')
        require(base.startsWith("https://") || base.startsWith("http://")) {
            "Engine address is invalid"
        }
        CookieManager.getInstance().setAcceptCookie(true)

        return withContext(Dispatchers.Main) {
            val scope = CoroutineScope(Dispatchers.IO)
            suspendCancellableCoroutine { continuation ->
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
                    // The cookie is set on the cross-origin redirect chain, so it
                    // is read on every finished page, not only the last one.
                    override fun onPageFinished(view: WebView, url: String?) {
                        val cookie = CookieManager.getInstance().getCookie(base).orEmpty()
                        if (cookie.contains(SESSION_COOKIE)) {
                            // Persist off the main thread; the flow the gate reads
                            // only updates once this write lands.
                            scope.launch {
                                runCatching { save(context, base, cookie) }
                                finish(Result.success(cookie))
                            }
                        }
                    }

                    // Without this a failed load left the user staring at a
                    // blank page with the button spinning and no way to tell
                    // what went wrong.
                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        if (!request.isForMainFrame) return
                        finish(Result.failure(IOException("Could not reach the sign-in page: ${error.description}")))
                    }

                    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                        // Cancelling leaves the page blank, so end the flow with
                        // a reason instead.
                        handler.cancel()
                        finish(Result.failure(IOException("The sign-in page's certificate was not trusted (${error.primaryError})")))
                    }
                }
                continuation.invokeOnCancellation { finish(Result.failure(CancellationException())) }
                try {
                    attach(view)
                    view.loadUrl(loginEntryUrl(base))
                } catch (error: Throwable) {
                    finish(Result.failure(error))
                }
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
