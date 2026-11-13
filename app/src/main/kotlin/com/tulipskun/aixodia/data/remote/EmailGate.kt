package com.tulipskun.aixodia.data.remote

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * The email gate described in `auth_system.md`.
 *
 * Beside [AccessLogin] (which runs the browser first and only then gets a
 * token), this path issues the token up front and leaves it inert until the user
 * confirms it in a browser behind Cloudflare Access:
 *
 * 1. [request] sends the address. The engine answers with a token in state
 *    `pending` plus a `reference` for the confirmation page.
 * 2. The app opens that page. Access runs the login there.
 * 3. [status] reports `pending` until the user presses Confirm, then `active`.
 *
 * The app must not treat a `pending` token as signed in: the engine refuses it
 * on every authenticated route. That is why [status] is polled rather than
 * assumed.
 */
object EmailGate {
    private const val REQUEST_PATH = "/v1/auth/request"
    private const val STATUS_PATH = "/v1/auth/status"

    private val client: OkHttpClient by lazy { OkHttpClient() }

    /** What the engine answers once it accepts the address. */
    data class Issued(
        /** The token. Inert until confirmed, but safe to store now. */
        val token: String,
        /** Path to open in the browser; the engine supplies it so the app does
         *  not have to know the route. */
        val confirmPath: String,
        /** Absolute URL to open. */
        val confirmUrl: String,
    )

    /** The engine's answer to a status poll. */
    enum class State {
        Pending,
        Active,
        Expired,
        Revoked,
        /** The engine no longer knows the token at all (it restarted, most
         *  likely — sessions are held in memory). */
        Unknown,
    }

    /**
     * A refusal worth showing to the user, with the code the engine sent.
     *
     * The codes are differentiated because they mean different things to the
     * person typing: `EMAIL_NOT_ALLOWED` is "this address is not on the list",
     * `NO_ALLOWLIST` is "the engine was never configured" (an operator problem,
     * not the user's), and `BAD_EMAIL` is a typo.
     */
    class Refused(val code: String, message: String) : IOException(message)

    /**
     * Asks the engine for a token for [email].
     *
     * @throws Refused when the engine declines, with a code the caller can turn
     *   into a specific message.
     */
    suspend fun request(origin: String, email: String): Issued = withContext(Dispatchers.IO) {
        val body = JSONObject().put("email", email.trim()).toString()
            .toRequestBody("application/json".toMediaType())
        val http = Request.Builder()
            .url(origin.trimEnd('/') + REQUEST_PATH)
            .header("Accept", "application/json")
            .post(body)
            .build()

        client.newCall(http).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = parseObject(text)
                ?: throw Refused("NOT_JSON", describeNonJson(response.code, text))
            if (!response.isSuccessful) {
                val code = json.optString("code").ifBlank { "HTTP_${response.code}" }
                throw Refused(code, explain(code))
            }
            val token = json.getString("token")
            val path = json.getString("confirm_path")
            // The engine reports the path, not a full URL: it does not know the
            // hostname the app reaches it by (it may be the tunnel or a LAN
            // address), so the app composes the URL from the origin it used.
            Issued(token = token, confirmPath = path, confirmUrl = origin.trimEnd('/') + path)
        }
    }

    /**
     * Parses a body that should be JSON, or says why it was not.
     *
     * A body starting with `<` is HTML, and the only thing that answers with HTML
     * in front of the engine is Cloudflare Access: the hostname is protected as a
     * whole, so a request to a public engine route is intercepted and redirected
     * to the login page before the engine ever sees it. Reporting that as
     * "JSONObject cannot be converted" sends anyone reading the error looking for
     * a parsing bug instead of a policy one, so it is named here.
     */
    private fun parseObject(text: String): JSONObject? =
        runCatching { JSONObject(text) }.getOrNull()

    private fun describeNonJson(status: Int, text: String): String {
        val trimmed = text.trimStart()
        return if (trimmed.startsWith("<")) {
            "Cloudflare Access is intercepting this request (HTTP $status). " +
                "The engine hostname is protected as a whole, so sign-in cannot reach " +
                "/v1/auth/request. Exempt that path from Access."
        } else {
            "The engine answered with something that is not JSON (HTTP $status)."
        }
    }

    /** Polls the state of [token]. */
    suspend fun status(origin: String, token: String): State = withContext(Dispatchers.IO) {
        val http = Request.Builder()
            .url(origin.trimEnd('/') + STATUS_PATH)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        client.newCall(http).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = parseObject(text)
            if (!response.isSuccessful) {
                // A token the engine has never heard of is not an error here: it
                // is one of the states the caller waits out.
                if (json?.optString("code") == "UNKNOWN_TOKEN") return@withContext State.Unknown
                throw IOException(describeNonJson(response.code, text))
            }
            if (json == null) throw IOException(describeNonJson(response.code, text))
            when (json.optString("state")) {
                "pending" -> State.Pending
                "active" -> State.Active
                "expired" -> State.Expired
                "revoked" -> State.Revoked
                else -> State.Unknown
            }
        }
    }

    /** A message for the user, keyed by the engine's refusal code. */
    private fun explain(code: String): String = when (code) {
        "EMAIL_NOT_ALLOWED" -> "That email is not allowed to sign in to this engine."
        "BAD_EMAIL" -> "That does not look like an email address."
        "NO_ALLOWLIST" -> "This engine has no allowed emails configured yet."
        else -> "The engine refused the request ($code)."
    }
}
