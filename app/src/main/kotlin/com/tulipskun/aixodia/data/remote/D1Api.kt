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
 * Cloudflare D1 over its REST API. D1 is the chat store (the engine has no
 * local database), so the phone reads two things from it straight:
 * the daemon's own row out of `system_state` so it knows where the quick
 * tunnel is (`node()`), and — when the tunnel cannot be dialled — the chat
 * list and the pages of a thread out of `chat_sessions`/`chat_turns`
 * (`chatSessions()`, `chatTurns()`), the same tables the engine serves on
 * `/v1/...`. The tables carry the columns the engine's schema ensures
 * (`generation_ms`, `reasoning_content`, `block`), so one SELECT shape covers
 * every database the engine could be talking to.
 *
 * One credential is enough: the account and the database are resolved from the
 * token with the same three calls the engine makes (`/user/tokens/verify`,
 * then `/accounts`, then `/accounts/{id}/d1/database`) and cached in settings,
 * so an operator never types a Cloudflare id.
 *
 * Chat writes stay on the engine: a phone-side insert would race the engine's
 * seq counter, and only the engine can hand a turn to the model.
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
    private suspend fun exec(token: String, target: Target, sql: String, params: JSONArray): Rows =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().put("sql", sql).put("params", params).toString()
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

    /**
     * One statement against the current token and database. Params bind on
     * their own JSON type: D1 hands a quoted "301" to SQLite as text, and
     * integers sort before text, so a page cursor sent as a string would make
     * every numeric seq count as "less than" it and the page would start at
     * the chat's first rows. Numbers and booleans leave here as numbers.
     */
    private suspend fun query(sql: String, vararg params: Any?): Rows {
        val bound = JSONArray()
        for (p in params) bound.put(p ?: JSONObject.NULL)
        return query(sql, bound)
    }

    private suspend fun query(sql: String, params: JSONArray): Rows {
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
            val url = query("SELECT value FROM system_state WHERE key = ?", "daemon/url")
                .rows.firstOrNull()?.optString("value")?.trim().orEmpty()
            if (url.isBlank()) null
            else Triple(
                url,
                query("SELECT value FROM system_state WHERE key = ?", "daemon/version")
                    .rows.firstOrNull()?.optString("value").orEmpty(),
                query("SELECT value FROM system_state WHERE key = ?", "daemon/heartbeat")
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

    // ------------------------------------------------------ chat store reads

    /**
     * Every chat, newest first — a straight read of the tables the engine
     * writes. It is the same SELECT the engine serves on `/v1/sessions`: the
     * usage is summed by the database over every stored turn, so the strip
     * shows the engine's own numbers even when the phone reads around it.
     */
    suspend fun chatSessions(): List<JSONObject> = query(SESSION_SELECT).rows

    /**
     * One chat's turns: the newest page ending just before [beforeSeq]
     * (`<= 0` means the newest page), oldest-first — the same shape the
     * engine's `/v1/sessions/{id}/turns` answers with. [limit] `<= 0` reads
     * the whole transcript, which the incremental reconnect catch-up needs.
     */
    suspend fun chatTurns(id: String, beforeSeq: Long, limit: Int): List<JSONObject> {
        if (limit <= 0) {
            return query(
                "SELECT $TURN_COLUMNS FROM chat_turns WHERE session_id = ? ORDER BY seq ASC",
                id,
            ).rows
        }
        val page = if (beforeSeq > 0) {
            query(
                "SELECT $TURN_COLUMNS FROM chat_turns WHERE session_id = ? AND seq < ? ORDER BY seq DESC LIMIT ?",
                id, beforeSeq, limit,
            ).rows
        } else {
            query(
                "SELECT $TURN_COLUMNS FROM chat_turns WHERE session_id = ? ORDER BY seq DESC LIMIT ?",
                id, limit,
            ).rows
        }
        // SQL walked backwards so LIMIT could take the page nearest the
        // cursor; a transcript is read oldest-first.
        return page.reversed()
    }

    companion object {
        /** The one Cloudflare endpoint every call is addressed with. */
        const val API_BASE = "https://api.cloudflare.com/client/v4"

        /** The database `discover` prefers when the token can see several. */
        private const val DEFAULT_DATABASE = "aixodia"

        /** A chat_turns row's columns, in the order the reader maps them. */
        internal const val TURN_COLUMNS =
            "id,session_id,seq,role,agent,job_id,text,created_at,model,input_tokens," +
                "output_tokens,duration_ms,cache_read_tokens,cache_write_tokens," +
                "reasoning_tokens,input_includes_cache,generation_ms,reasoning_content,block"

        /**
         * The engine's session list SELECT, verbatim (`session/d1.rs`): D1's own
         * SQL adds the usage the strip draws instead of the phone summing
         * whichever rows happen to be loaded.
         */
        private const val SESSION_SELECT = """
            SELECT s.id,s.title,s.provider,s.model,s.reasoning,s.temperature,s.top_p,s.max_tokens,s.created_at,s.updated_at,
                   COALESCE(u.prompt_tokens,0) AS usage_prompt_tokens,
                   COALESCE(u.cache_read,0) AS usage_cache_read,
                   COALESCE(u.fresh_input,0) AS usage_fresh_input,
                   COALESCE(u.output_tokens,0) AS usage_output_tokens,
                   COALESCE(u.total_ms,0) AS usage_total_ms,
                   COALESCE((
                       SELECT t.duration_ms FROM chat_turns t
                       WHERE t.session_id=s.id AND t.role != 'user' AND t.role NOT LIKE 'tool%'
                             AND t.duration_ms > 0
                       ORDER BY t.seq DESC LIMIT 1
                   ),0) AS usage_last_turn_ms
            FROM chat_sessions s
            LEFT JOIN (
                SELECT session_id,
                       SUM(CASE WHEN input_includes_cache = 1 THEN input_tokens
                                ELSE input_tokens + cache_read_tokens + cache_write_tokens END) AS prompt_tokens,
                       SUM(cache_read_tokens) AS cache_read,
                       SUM(CASE WHEN input_includes_cache = 1
                                THEN MAX(input_tokens - cache_read_tokens, 0)
                                ELSE input_tokens + cache_write_tokens END) AS fresh_input,
                       SUM(output_tokens) AS output_tokens,
                       SUM(duration_ms) AS total_ms
                FROM chat_turns
                WHERE role != 'user' AND role NOT LIKE 'tool%'
                GROUP BY session_id
            ) u ON u.session_id = s.id
            ORDER BY s.updated_at DESC, s.id DESC LIMIT 200"""
    }
}
