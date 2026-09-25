package aixodia

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlin.js.Promise
import kotlin.js.undefined

/**
 * The worker's fetch entry point. Cloudflare calls it with the request and the
 * bindings; the rest is Kotlin that talks to the Worker runtime through the Web
 * API (`Request`, `Response`, `URL`, `fetch`) and D1. Kotlin has no literals for
 * JavaScript objects, so [obj], [stringify] and [decode] are the only shims this
 * file needs.
 */
@JsExport
fun handleFetch(request: Any?, env: Any?): Promise<Any?> =
    GlobalScope.promise { route(request, env) }

private suspend fun route(request: dynamic, env: dynamic): dynamic {
    val url = js("new URL")(request.url)
    val path: String = url.pathname.toString()
    val method: String = request.method.toString()

    if (path == "/ws" && present(env.AI_DAEMON_WS)) {
        val session: String = url.searchParams.get("session")?.toString() ?: "default"
        val headers = obj("Authorization" to ((request.headers.get("Authorization") ?: "").toString()))
        val target = (env.AI_DAEMON_WS as String) + "?session=" + encode(session)
        return js("fetch")(target, obj("headers" to headers))
    }
    if (!path.startsWith("/api/")) return text("AIxodia Worker", 200)
    val auth: String = request.headers.get("Authorization") ?: ""
    if (auth != "Bearer ${env.AIXODIA_TOKEN}") return json(obj("error" to "unauthorized"), 401)

    val db = env.DB

    if (path == "/api/node" && method == "GET") {
        val row = first(db, "SELECT tunnel_url, version, heartbeat FROM nodes WHERE id = 'ai'")
        val now: Long = (js("Date.now")() as Double / 1000.0).toLong()
        val tunnelUrl: String? = row?.tunnel_url?.toString()
        val version: String = row?.version?.toString() ?: ""
        val age: Long = if (row == null) -1L else now - number(row.heartbeat).toLong()
        val online: Boolean = age >= 0L && age < 90L
        return json(obj("tunnel_url" to tunnelUrl, "version" to version, "heartbeat_age_s" to age, "online" to online))
    }
    if (path == "/api/node/heartbeat" && method == "POST") {
        val body = body(request)
        val tunnel: String = body.tunnel_url?.toString() ?: ""
        if (!TUNNEL_RE.containsMatchIn(tunnel)) {
            return json(obj("error" to "tunnel_url must be https://*.trycloudflare.com"), 400)
        }
        run(
            db,
            "INSERT INTO nodes(id, tunnel_url, version, heartbeat) VALUES('ai', ?, ?, unixepoch()) " +
                "ON CONFLICT(id) DO UPDATE SET tunnel_url = excluded.tunnel_url, " +
                "version = excluded.version, heartbeat = unixepoch()",
            tunnel,
            body.version?.toString() ?: "",
        )
        return json(obj("ok" to true))
    }
    if (path == "/api/ping" && method == "GET") {
        return json(obj("ok" to true, "service" to "aixodia"))
    }

    if (path == "/api/state" && method == "GET") {
        val prefix: String = url.searchParams.get("prefix")?.toString() ?: ""
        if (prefix.length > 128 || !PREFIX_RE.containsMatchIn(prefix)) {
            return json(obj("error" to "bad prefix"), 400)
        }
        val result = all(db, "SELECT key FROM state WHERE key >= ? ORDER BY key LIMIT 200", prefix)
        val keys: List<String> = rows(result).map { it.key.toString() }
        return json(obj("keys" to keys))
    }
    val stateKey = afterPrefix(path, "/api/state/")
    if (stateKey != null) {
        val key = decode(stateKey)
        if (!KEY_RE.containsMatchIn(key)) return json(obj("error" to "bad key"), 400)
        if (method == "GET") {
            val row = first(db, "SELECT value, updated_at FROM state WHERE key = ?", key)
            val value: String? = row?.value?.toString()
            val updatedAt: Long = if (row == null) 0L else number(row.updated_at).toLong()
            return json(obj("key" to key, "value" to value, "updated_at" to updatedAt))
        }
        if (method == "PUT") {
            val value = valueOf(body(request))
            if (value.length > 500_000) return json(obj("error" to "too large (500KB max)"), 413)
            run(
                db,
                "INSERT INTO state(key, value, updated_at) VALUES(?, ?, unixepoch()) " +
                    "ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = unixepoch()",
                key,
                value,
            )
            return json(obj("ok" to true))
        }
    }

    if (path == "/api/devices" && method == "GET") {
        val result = all(db, "SELECT id, label, created_at, revoked FROM devices ORDER BY created_at DESC LIMIT 100")
        return rawJson(result.results)
    }
    if (path == "/api/devices" && method == "POST") {
        val payload = body(request)
        val id: String = payload.id?.toString() ?: ""
        if (id.isBlank() || !KEY_RE.containsMatchIn(id)) return json(obj("error" to "id required"), 400)
        run(db, "INSERT OR IGNORE INTO devices(id, label) VALUES(?, ?)", id, payload.label?.toString() ?: "")
        return json(obj("ok" to true))
    }
    val device = afterPrefix(path, "/api/devices/")
    if (device != null && device.endsWith("/revoke") && method == "POST") {
        run(db, "UPDATE devices SET revoked = 1 WHERE id = ?", decode(device.removeSuffix("/revoke")))
        return json(obj("ok" to true))
    }

    if (path == "/api/sessions" && method == "GET") {
        val result = all(
            db,
            "SELECT id, title, provider, model, created_at, updated_at FROM sessions " +
                "ORDER BY updated_at DESC LIMIT 200",
        )
        return rawJson(result.results)
    }
    if (path == "/api/sessions" && method == "POST") {
        val payload = body(request)
        val requested: String = payload.id?.toString()?.trim().orEmpty()
        val id = if (requested.isNotEmpty()) requested else "s" + (js("Date.now")() as Double).toLong().toString(36)
        if (!KEY_RE.containsMatchIn(id)) return json(obj("error" to "bad id"), 400)
        run(
            db,
            "INSERT OR IGNORE INTO sessions(id, title, model, created_at, updated_at) " +
                "VALUES(?, ?, ?, unixepoch(), unixepoch())",
            id,
            payload.title?.toString() ?: id,
            payload.model?.toString() ?: "",
        )
        val row = first(db, "SELECT id, title, provider, model, created_at, updated_at FROM sessions WHERE id = ?", id)
        return rawJson(row ?: obj("id" to id))
    }

    val session = afterPrefix(path, "/api/sessions/")
    if (session != null && !session.endsWith("/turns")) {
        val sid = decode(session)
        if (method == "PATCH") {
            val title = (body(request).title?.toString() ?: "").trim().take(120)
            if (title.isEmpty()) return json(obj("error" to "title required"), 400)
            val info = run(db, "UPDATE sessions SET title = ?, updated_at = unixepoch() WHERE id = ?", title, sid)
            if (!changed(info)) return json(obj("error" to "not_found"), 404)
            return json(obj("ok" to true, "id" to sid, "title" to title))
        }
        if (method == "DELETE") {
            run(db, "DELETE FROM turns WHERE session_id = ?", sid)
            run(db, "DELETE FROM state WHERE key = ?", "sessions/$sid")
            val info = run(db, "DELETE FROM sessions WHERE id = ?", sid)
            if (!changed(info)) return json(obj("error" to "not_found"), 404)
            return json(obj("ok" to true, "id" to sid))
        }
    }
    val turns = session?.takeIf { it.endsWith("/turns") }?.removeSuffix("/turns")
    if (turns != null) {
        val sid = decode(turns)
        if (method == "GET") {
            val before = url.searchParams.get("before_seq")?.toString()?.toDoubleOrNull() ?: 9.007199254740991E15
            val limit = (url.searchParams.get("limit")?.toString()?.toDoubleOrNull() ?: 50.0).coerceAtMost(200.0)
            val result = all(
                db,
                "SELECT seq, role, agent, job_id, text, created_at FROM turns " +
                    "WHERE session_id = ? AND seq < ? ORDER BY seq DESC LIMIT ?",
                sid,
                before.toLong(),
                limit.toLong(),
            )
            val list: List<dynamic> = rows(result).reversed()
            return rawJson(obj("turns" to list))
        }
        if (method == "POST") {
            val payload = body(request)
            val role: String = payload.role?.toString() ?: "model"
            run(db, "INSERT OR IGNORE INTO sessions(id, title, updated_at) VALUES(?, ?, unixepoch())", sid, sid)
            if (role == "user") {
                val titled = first(db, "SELECT title FROM sessions WHERE id = ?", sid)
                val current: String = titled?.title?.toString() ?: ""
                if (titled != null && (current.isEmpty() || current == sid)) {
                    val text = (payload.text?.toString() ?: "").take(42)
                    run(db, "UPDATE sessions SET title = ? WHERE id = ?", if (text.isNotEmpty()) text else sid, sid)
                }
            }
            val max = first(db, "SELECT COALESCE(MAX(seq),0) AS m FROM turns WHERE session_id = ?", sid)
            val next: Long = number(max?.m).toLong() + 1L
            val seq: Long = next
            run(
                db,
                "INSERT INTO turns(session_id, seq, role, agent, job_id, text) VALUES(?,?,?,?,?,?)",
                sid,
                seq,
                role,
                payload.agent?.toString() ?: "",
                payload.job_id?.toString() ?: "",
                payload.text?.toString() ?: "",
            )
            run(db, "UPDATE sessions SET updated_at = unixepoch() WHERE id = ?", sid)
            return json(obj("seq" to seq))
        }
    }
    return json(obj("error" to "not_found"), 404)
}

private val KEY_RE = Regex("^[A-Za-z0-9:_-]{1,128}$")
private val PREFIX_RE = Regex("^[A-Za-z0-9:_/-]*$")
private val TUNNEL_RE = Regex("^https://[A-Za-z0-9.-]+\\.trycloudflare\\.com$")

/** The path after a prefix, or null when the path does not start with it. */
private fun afterPrefix(path: String, prefix: String): String? {
    if (!path.startsWith(prefix)) return null
    val rest = path.removePrefix(prefix)
    return if (rest.isEmpty()) null else rest
}

private fun present(value: dynamic): Boolean = value != null && value != undefined

private fun number(value: dynamic?): Double = (value as Number?)?.toDouble() ?: 0.0

/** A JavaScript object literal, which Kotlin itself has no syntax for. */
private fun obj(vararg pairs: Pair<String, Any?>): dynamic {
    val target = js("({})")
    for ((name, value) in pairs) {
        target[name] = value
    }
    return target
}

private fun stringify(value: Any?): String = js("JSON.stringify")(value).toString()

private fun encode(value: String): String = js("encodeURIComponent")(value).toString()

private fun decode(value: String): String = js("decodeURIComponent")(value).toString()

private fun isString(value: Any?): Boolean = value is String

private fun valueOf(body: dynamic): String {
    val value = body.value
    if (!present(value)) return "null"
    if (isString(value)) return value.toString()
    return stringify(value)
}

private fun changed(info: dynamic): Boolean = number(info?.meta?.changes) > 0.0

private fun json(value: dynamic, status: Int = 200): dynamic = rawJson(value, status)

private fun rawJson(value: dynamic, status: Int = 200): dynamic {
    val payload = stringify(value)
    val headers = obj("content-type" to "application/json")
    return js("new Response")(payload, obj("status" to status, "headers" to headers))
}

@Suppress("UNCHECKED_CAST")
private fun <T> Any?.unsafeCast(): T = this as T

private fun text(body: String, status: Int): dynamic =
    js("new Response")(body, obj("status" to status))

private suspend fun body(request: dynamic): dynamic = try {
    (request.json() as Any?).unsafeCast<Promise<Any?>>().await()
} catch (_: Throwable) {
    obj()
}

private fun statement(db: dynamic, sql: String, args: Array<out Any?>): dynamic {
    val prepared = db.prepare(sql)
    // Dynamic calls take no spread operator, and no statement here needs more
    // than three parameters.
    when (args.size) {
        0 -> Unit
        1 -> prepared.bind(args[0])
        2 -> prepared.bind(args[0], args[1])
        3 -> prepared.bind(args[0], args[1], args[2])
        else -> prepared.bind(args)
    }
    return prepared
}

private suspend fun await(value: dynamic): dynamic =
    (value as Any?).unsafeCast<Promise<Any?>>().await()

private suspend fun first(db: dynamic, sql: String, vararg args: Any?): dynamic? =
    await(statement(db, sql, args).first())

private suspend fun all(db: dynamic, sql: String, vararg args: Any?): dynamic =
    await(statement(db, sql, args).all())

private suspend fun run(db: dynamic, sql: String, vararg args: Any?): dynamic =
    await(statement(db, sql, args).run())

private fun rows(result: dynamic): List<dynamic> {
    val value = result.results
    if (!present(value)) return emptyList()
    val length = number(value.length).toInt()
    return (0 until length).map { value[it] }
}
