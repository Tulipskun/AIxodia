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
 * Cloudflare D1 over its REST API (D-011, AX-010). The phone reads and writes
 * the history tables itself, so the Worker that used to sit in front of D1 is
 * gone. The daemon has talked to this same API since CHANGE-081, which is why
 * nothing new has to be deployed for the app to do it too.
 *
 * One credential is enough: the account and the database are resolved from the
 * token with the same three calls the daemon makes (`/user/tokens/verify`, then
 * `/accounts`, then `/accounts/{id}/d1/database`) and cached in settings, so an
 * operator never types a Cloudflare id.
 *
 * This class owns history only. Provider keys, agent settings and per-chat
 * model pins stay on the daemon, because validating them needs the running
 * router rather than the database.
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
            val req = Request.Builder()
                .url("$API_BASE/accounts/${target.accountId}/d1/database/${target.databaseId}/query")
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                val env = runCatching { JSONObject(text) }.getOrNull()
                if (r.code == 401 || r.code == 403) {
                    throw IllegalStateException("$r.code token ไม่ผ่านหรือไม่มีสิทธิ์ D1")
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
        val req = Request.Builder()
            .url(API_BASE + path)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val env = runCatching { JSONObject(text) }.getOrNull()
            if (r.code == 401 || r.code == 403) {
                throw IllegalStateException("$r.code token ไม่ผ่านหรือไม่มีสิทธิ์ที่ต้องการ")
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
     * daemon does (REQ-046(3)), and remembers the answer. A stored id wins only
     * while the token can still see it, so moving to another account repairs
     * itself instead of failing forever.
     */
    suspend fun discover(): Target {
        val stored = settings.current()
        val token = stored.token
        if (token.isBlank()) throw IllegalStateException("ยังไม่ได้ใส่ Cloudflare API token")

        val verify = get(token, "/user/tokens/verify")
        val status = verify.optJSONObject("result")?.optString("status").orEmpty()
        if (status.isNotEmpty() && status != "active") {
            throw IllegalStateException("token สถานะ $status")
        }

        val accounts = objects(get(token, "/accounts").optJSONArray("result"))
        if (accounts.isEmpty()) throw IllegalStateException("token นี้มองไม่เห็น Cloudflare account")
        val account = accounts.firstOrNull { it.optString("id") == stored.accountId }
            ?: accounts.firstOrNull { it.optString("name") == DEFAULT_DATABASE }
            ?: accounts.first()

        val databases = objects(
            get(token, "/accounts/${account.optString("id")}/d1/database").optJSONArray("result"),
        )
        if (databases.isEmpty()) {
            throw IllegalStateException("account นี้ไม่มี D1 database ที่ token มองเห็น")
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
        if (token.isBlank()) throw IllegalStateException("ยังไม่ได้ใส่ Cloudflare API token")
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

    // ---------------------------------------------------------------- history

    /** The session list, newest first (AX-011). */
    suspend fun sessions(): List<SessionRow> = query(
        "SELECT id, title, provider, model, created_at, updated_at FROM sessions " +
            "ORDER BY updated_at DESC LIMIT 200",
    ).rows.map {
        SessionRow(
            id = it.optString("id"),
            title = it.optString("title"),
            model = it.optString("model"),
            provider = it.optString("provider"),
            updatedAt = it.optLong("updated_at"),
        )
    }

    /** Creates the chat in D1 too, so the drawer and the daemon agree (AX-063). */
    suspend fun createSession(id: String, title: String): Boolean {
        query(
            "INSERT OR IGNORE INTO sessions(id, title, model, created_at, updated_at) " +
                "VALUES(?, ?, '', unixepoch(), unixepoch())",
            listOf(id, title),
        )
        return true
    }

    /** Renames a chat, the way Gemini/ChatGPT let you retitle a conversation. */
    suspend fun renameSession(sessionId: String, title: String): Boolean = query(
        "UPDATE sessions SET title = ?, updated_at = unixepoch() WHERE id = ?",
        listOf(title, sessionId),
    ).changes > 0

    /**
     * Removes the chat, its turns and its state row — the same three deletes the
     * Worker used to run, one statement at a time so a failure names itself.
     */
    suspend fun deleteSession(sessionId: String): Boolean {
        query("DELETE FROM turns WHERE session_id = ?", listOf(sessionId))
        query("DELETE FROM state WHERE key = ?", listOf("sessions/$sessionId"))
        return query("DELETE FROM sessions WHERE id = ?", listOf(sessionId)).changes > 0
    }

    /** One page of history, oldest-first, the way the thread renders (AX-011). */
    suspend fun turns(sessionId: String, beforeSeq: Long, limit: Int): List<TurnRow> {
        val capped = limit.coerceIn(1, 200).toString()
        val page = if (beforeSeq <= 0L) {
            query(
                "$TURN_COLUMNS WHERE session_id = ? ORDER BY seq DESC LIMIT ?",
                listOf(sessionId, capped),
            )
        } else {
            query(
                "$TURN_COLUMNS WHERE session_id = ? AND seq < ? ORDER BY seq DESC LIMIT ?",
                listOf(sessionId, beforeSeq.toString(), capped),
            )
        }
        return page.rows.map { row ->
            TurnRow(
                seq = row.optLong("seq"),
                role = row.optString("role"),
                agent = row.optString("agent"),
                jobId = row.optString("job_id"),
                text = row.optString("text"),
                createdAt = row.optLong("created_at"),
                model = row.optString("model"),
                inputTokens = row.optInt("input_tokens"),
                outputTokens = row.optInt("output_tokens"),
                cacheRead = row.optInt("cache_read_tokens"),
                cacheWrite = row.optInt("cache_write_tokens"),
                durationMs = row.optLong("duration_ms"),
            )
        }.reversed()
    }

    /** Where the daemon is now, read straight from the `nodes` row (AX-050). */
    suspend fun node(): NodeInfo? {
        val row = query("SELECT tunnel_url, version, heartbeat FROM nodes WHERE id = 'ai'")
            .rows.firstOrNull() ?: return null
        val heartbeat = row.optLong("heartbeat")
        val age = System.currentTimeMillis() / 1000 - heartbeat
        return NodeInfo(
            tunnelUrl = row.optString("tunnel_url"),
            version = row.optString("version"),
            online = heartbeat > 0L && age in 0L..90L,
            ageS = age,
        )
    }

    /**
     * One cheap query for the settings screen: how many chats this token can
     * read. It throws with a readable reason, so the screen can say why not.
     */
    suspend fun ping(): Int =
        query("SELECT COUNT(*) AS n FROM sessions").rows.firstOrNull()?.optInt("n") ?: 0

    companion object {
        /** The one Cloudflare endpoint every call is addressed with. */
        const val API_BASE = "https://api.cloudflare.com/client/v4"

        /** The database `discover` prefers when the token can see several. */
        private const val DEFAULT_DATABASE = "aixodia"

        private const val TURN_COLUMNS =
            "SELECT seq, role, agent, job_id, text, created_at, model, " +
                "input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, " +
                "duration_ms FROM turns"
    }
}
