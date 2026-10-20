package com.tulipskun.aixodia.data.remote

import com.tulipskun.aixodia.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cloudflare D1 over its REST API. The phone reads and writes
 * the history tables itself, so the Worker that used to sit in front of D1 is
 * gone. The daemon has talked to this same API since CHANGE-081, which is why
 * nothing new has to be deployed for the app to do it too.
 *
 * One credential is enough: the account and the database are resolved from the
 * token with the same three calls the daemon makes (`/user/tokens/verify`, then
 * `/accounts`, then `/accounts/{id}/d1/database`) and cached in settings, so an
 * operator never types a Cloudflare id.
 *
 * This class owns what has to be shared off the machine: the daemon's own row,
 * i.e. the tunnel URL the phone dials. Chat history is the daemon's local
 * store; provider keys and settings live there too, because validating them
 * needs the running router rather than the database.
 */
class D1Api(private val settings: SettingsStore) {

    /** One statement's outcome: its rows plus D1's own bookkeeping. */
    data class Rows(val rows: List<JSONObject>, val changes: Int, val lastRowId: Long)

    /** Where the token reaches: the pair every D1 call is addressed with. */
    data class Target(
        val accountId: String,
        val accountName: String,
        val databaseId: String,
        val databaseName: String,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    // The resolved pair is cached per token, like the daemon does: pulling a
    // page must not pay three discovery round trips, and a rotated token
    // resolves itself again.
    private var cachedTarget: Target? = null
    private var cachedFor: String = ""

    // ------------------------------------------------------------ D1 transport

    /** Runs one statement: reads and writes share all of their failure handling. */
    private suspend fun exec(token: String, target: Target, sql: String, params: List<String>): Rows =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().put("sql", sql).put("params", JSONArray(params)).toString()
            val reqBuilder = Request.Builder()
                .url("$API_BASE/accounts/${target.accountId}/d1/database/${target.databaseId}/query")
                .header("Content-Type", "application/json")
            if (token.contains(":")) {
                val parts = token.split(":", limit = 2)
                reqBuilder.header("X-Auth-Email", parts[0])
                reqBuilder.header("X-Auth-Key", parts[1])
            } else {
                reqBuilder.header("Authorization", "Bearer $token")
            }
            val req = reqBuilder
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                val env = runCatching { JSONObject(text) }.getOrNull()
                if (r.code == 401 || r.code == 403) {
                    throw IllegalStateException("$r.code token rejected or no D1 permission")
                }
                if (env == null) throw IllegalStateException("HTTP ${r.code}")
                if (!r.isSuccessful || !env.optBoolean("success")) {
                    throw IllegalStateException(failure(env, r.code))
                }
                val sets = env.optJSONArray("result")
                val first = if (sets != null && sets.length() > 0) sets.optJSONObject(0) else null
                if (first == null) return@use Rows(emptyList(), 0, 0L)
                val raw = first.optJSONArray("results")
                val rows = ArrayList<JSONObject>()
                if (raw != null) {
                    for (i in 0 until raw.length()) raw.optJSONObject(i)?.let { rows.add(it) }
                }
                val meta = first.optJSONObject("meta")
                Rows(rows, meta?.optInt("changes") ?: 0, meta?.optLong("last_row_id") ?: 0L)
            }
        }

    /** A plain Cloudflare API GET, used only by discovery. */
    private suspend fun get(token: String, path: String): JSONObject = withContext(Dispatchers.IO) {
        val reqBuilder = Request.Builder()
            .url(API_BASE + path)
            .header("Accept", "application/json")
        if (token.contains(":")) {
            val parts = token.split(":", limit = 2)
            reqBuilder.header("X-Auth-Email", parts[0])
            reqBuilder.header("X-Auth-Key", parts[1])
        } else {
            reqBuilder.header("Authorization", "Bearer $token")
        }
        val req = reqBuilder.build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val env = runCatching { JSONObject(text) }.getOrNull()
            if (r.code == 401 || r.code == 403) {
                throw IllegalStateException("$r.code token rejected or insufficient permission")
            }
            if (env == null) throw IllegalStateException("HTTP ${r.code}")
            if (!r.isSuccessful) throw IllegalStateException(failure(env, r.code))
            env
        }
    }

    /** The daemon's own wording for a rejected statement, never a bare status. */
    private fun failure(env: JSONObject?, code: Int): String {
        val errs = env?.optJSONArray("errors")
        val first = if (errs != null && errs.length() > 0) errs.optJSONObject(0) else null
        val message = first?.optString("message").orEmpty()
        return if (message.isBlank()) "HTTP $code" else "HTTP $code: $message"
    }

    private fun objects(array: JSONArray?): List<JSONObject> {
        if (array == null) return emptyList()
        val out = ArrayList<JSONObject>(array.length())
        for (i in 0 until array.length()) array.optJSONObject(i)?.let { out.add(it) }
        return out
    }

    // -------------------------------------------------------------- discovery

    /**
     * Resolves the account and the database from the token, exactly like the
     * daemon does, and remembers the answer. A stored id wins only
     * while the token can still see it, so moving to another account repairs
     * itself instead of failing forever.
     */
    suspend fun discover(): Target {
        val stored = settings.current()
        val token = stored.token
        if (token.isBlank()) throw IllegalStateException("Cloudflare API token not set")

        // /user/tokens/verify only answers for user-owned tokens. An account-owned
        // token is a normal server credential and answers 401 there even when it
        // is valid; the /accounts call below is the real proof. A user token that
        // was revoked or disabled still answers "not active" and is rejected.
        val status = runCatching {
            get(token, "/user/tokens/verify").optJSONObject("result")?.optString("status").orEmpty()
        }.getOrDefault("")
        if (status.isNotEmpty() && status != "active") {
            throw IllegalStateException("token status $status")
        }

        val accounts = objects(get(token, "/accounts").optJSONArray("result"))
        if (accounts.isEmpty()) throw IllegalStateException("this token cannot see any Cloudflare account")
        val account = accounts.firstOrNull { it.optString("id") == stored.accountId }
            ?: accounts.firstOrNull { it.optString("name") == DEFAULT_DATABASE }
            ?: accounts.first()

        val databases = objects(
            get(token, "/accounts/${account.optString("id")}/d1/database").optJSONArray("result"),
        )
        if (databases.isEmpty()) {
            throw IllegalStateException("this account has no D1 database visible to the token")
        }
        val database = databases.firstOrNull { it.optString("uuid") == stored.databaseId }
            ?: databases.firstOrNull { it.optString("name") == DEFAULT_DATABASE }
            ?: databases.first()

        val found = Target(
            accountId = account.optString("id"),
            accountName = account.optString("name"),
            databaseId = database.optString("uuid"),
            databaseName = database.optString("name"),
        )
        settings.saveResolvedTarget(found.accountId, found.databaseId)
        return found
    }

    /** The pair for this token: whatever is stored, or discovery on demand. */
    private suspend fun target(token: String): Target {
        cachedTarget?.let { if (cachedFor == token) return it }
        val stored = settings.current()
        val known = if (stored.accountId.isNotBlank() && stored.databaseId.isNotBlank()) {
            Target(stored.accountId, "", stored.databaseId, "")
        } else {
            discover()
        }
        cachedTarget = known
        cachedFor = token
        return known
    }

    /** One statement against the current token and database. */
    private suspend fun query(sql: String, params: List<String> = emptyList()): Rows {
        val token = settings.current().token
        if (token.isBlank()) throw IllegalStateException("Cloudflare API token not set")
        return try {
            exec(token, target(token), sql, params)
        } catch (first: IllegalStateException) {
            // A rotated token or a recreated database invalidates cached ids:
            // discover again before letting the error out.
            if (cachedTarget == null) throw first
            cachedTarget = null
            cachedFor = ""
            val fresh = discover()
            cachedTarget = fresh
            cachedFor = token
            exec(token, fresh, sql, params)
        }
    }

    /**
     * The daemon's single `tunnel` row: its public URL, its version (which
     * carries the start time) and its last heartbeat. ai-engine rewrites this
     * row every 30s, so the phone reads it once and counts uptime locally —
     * no periodic call is needed. This is the only daemon row; there is no
     * separate nodes table.
     */
    suspend fun node(): NodeInfo? {
        // New schema: daemon keys live in system_state. Fall back to the old
        // tunnel row when the database predates the rename (or the keys are absent).
        val triple = try {
            val url = query("SELECT value FROM system_state WHERE key = ?", listOf("daemon/url"))
                .rows.firstOrNull()?.optString("value")?.trim().orEmpty()
            if (url.isBlank()) null
            else Triple(
                url,
                query("SELECT value FROM system_state WHERE key = ?", listOf("daemon/version"))
                    .rows.firstOrNull()?.optString("value").orEmpty(),
                query("SELECT value FROM system_state WHERE key = ?", listOf("daemon/heartbeat"))
                    .rows.firstOrNull()?.optString("value").orEmpty(),
            )
        } catch (e: Exception) {
            if (!e.message.orEmpty().contains("no such table", ignoreCase = true)) throw e
            null
        }
        val (urlRaw, version, heartbeatStr) = if (triple != null) triple else {
            val row = query("SELECT url, version, heartbeat FROM tunnel LIMIT 1")
                .rows.firstOrNull() ?: return null
            Triple(row.optString("url"), row.optString("version"), row.optString("heartbeat"))
        }
        val url = urlRaw.trim().trimEnd('/')
        if (url.isBlank()) return null
        val started = Regex("started=(\\d+)").find(version)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val heartbeat = heartbeatStr.toLongOrNull() ?: 0L
        val ageS = if (heartbeat > 0L) nowSeconds() - heartbeat else -1L
        return NodeInfo(
            tunnelUrl = url,
            version = version,
            // The daemon rewrites this row every 30s. A beat older than 90s
            // means it stopped writing. A negative age (phone clock behind the
            // server's) still counts as alive.
            online = ageS < 90,
            ageS = ageS,
            startedAt = started,
        )
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    companion object {
        /** The one Cloudflare endpoint every call is addressed with. */
        const val API_BASE = "https://api.cloudflare.com/client/v4"

        /** The database `discover` prefers when the token can see several. */
        private const val DEFAULT_DATABASE = "aixodia"
    }
}
