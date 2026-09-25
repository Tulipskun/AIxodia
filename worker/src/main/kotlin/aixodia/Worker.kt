package aixodia

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.js.Promise

/**
 * The worker's fetch entry point. Cloudflare calls it with the request and the
 * bindings; everything else in this file is plain Kotlin that happens to talk to
 * the Worker runtime through the Web API (`Request`, `Response`, `URL`,
 * `fetch`) and D1.
 */
@JsExport
fun handleFetch(request: dynamic, env: dynamic): Promise<dynamic> =
    GlobalScope.promise { route(request, env) }

private suspend fun route(request: dynamic, env: dynamic): dynamic {
    val url = js("new URL")(request.url) as dynamic
    val path: String = url.pathname.toString()
    val method: String = request.method.toString()

    if (path == "/ws" && env.AI_DAEMON_WS != null && env.AI_DAEMON_WS != undefined) {
        val session = url.searchParams.get("session") ?: "default"
        return js("fetch")(env.AI_DAEMON_WS + "?session=" + encodeURIComponent(session), dynamic {
            headers = dynamic {
                Authorization = request.headers.get("Authorization") ?: ""
            }
        })
    }
    if (!path.startsWith("/api/")) return text("AIxodia Worker", 200)
    val auth: String = request.headers.get("Authorization") ?: ""
    if (auth != "Bearer ${env.AIXODIA_TOKEN}") return json(dynamic { error = "unauthorized" }, 401)

    val db = env.DB

    if (path == "/api/node" && method == "GET") {
        val row = db.prepare("SELECT tunnel_url, version, heartbeat FROM nodes WHERE id = 'ai'").first()
        val now = (js("Date.now")() as Double / 1000.0).toLong()
        val heartbeat: Double = row?.heartbeat ?: -1.0
        val age = if (row == null) -1L else now - heartbeat.toLong()
        return json(dynamic {
            tunnel_url = row?.tunnel_url ?: null
            version = row?.version ?: ""
            heartbeat_age_s = age
            online = age >= 0 && age < 90
        })
    }
    if (path == "/api/node/heartbeat" && method == "POST") {
        val body = jsonBody(request)
        val tunnel = body.tunnel_url?.toString() ?: ""
        if (!TUNNEL_RE.containsMatchIn(tunnel)) {
            return json(dynamic { error = "tunnel_url must be https://*.trycloudflare.com" }, 400)
        }
        db.prepare(
            "INSERT INTO nodes(id, tunnel_url, version, heartbeat) VALUES('ai', ?, ?, unixepoch()) " +
                "ON CONFLICT(id) DO UPDATE SET tunnel_url = excluded.tunnel_url, " +
                "version = excluded.version, heartbeat = unixepoch()",
        ).bind(tunnel, body.version?.toString() ?: "").run()
        return json(dynamic { ok = true })
    }
    if (path == "/api/ping" && method == "GET") {
        return json(dynamic { ok = true; service = "aixodia" })
    }

    if (path == "/api/state" && method == "GET") {
        val prefix: String = url.searchParams.get("prefix") ?: ""
        if (prefix.length > 128 || !PREFIX_RE.containsMatchIn(prefix)) {
            return json(dynamic { error = "bad prefix" }, 400)
        }
        val result = db.prepare("SELECT key FROM state WHERE key >= ? ORDER BY key LIMIT 200").bind(prefix).all()
        val keys = (result.results as Array<dynamic>).map { it.key.toString() }
        return json(dynamic { this.keys = keys })
    }
    val stateKey = matchPath(path, "/api/state/")
    if (stateKey != null) {
        val key = decodeURIComponent(stateKey)
        if (!KEY_RE.containsMatchIn(key)) return json(dynamic { error = "bad key" }, 400)
        if (method == "GET") {
            val row = db.prepare("SELECT value, updated_at FROM state WHERE key = ?").bind(key).first()
            return json(dynamic {
                this.key = key
                value = row?.value ?: null
                updated_at = row?.updated_at ?: 0
            })
        }
        if (method == "PUT") {
            val value = valueOf(jsonBody(request))
            if (value.length > 500_000) return json(dynamic { error = "too large (500KB max)" }, 413)
            db.prepare(
                "INSERT INTO state(key, value, updated_at) VALUES(?, ?, unixepoch()) " +
                    "ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = unixepoch()",
            ).bind(key, value).run()
            return json(dynamic { ok = true })
        }
    }

    if (path == "/api/devices" && method == "GET") {
        val result = db.prepare(
            "SELECT id, label, created_at, revoked FROM devices ORDER BY created_at DESC LIMIT 100",
        ).all()
        return rawJson(result.results)
    }
    if (path == "/api/devices" && method == "POST") {
        val body = jsonBody(request)
        val id = body.id?.toString() ?: ""
        if (id.isBlank() || !KEY_RE.containsMatchIn(id)) return json(dynamic { error = "id required" }, 400)
        db.prepare("INSERT OR IGNORE INTO devices(id, label) VALUES(?, ?)")
            .bind(id, body.label?.toString() ?: "").run()
        return json(dynamic { ok = true })
    }
    val revoke = matchPath(path, "/api/devices/")?.removeSuffix("/revoke")
    if (revoke != null && path.endsWith("/revoke") && method == "POST") {
        db.prepare("UPDATE devices SET revoked = 1 WHERE id = ?").bind(decodeURIComponent(revoke)).run()
        return json(dynamic { ok = true })
    }

    if (path == "/api/sessions" && method == "GET") {
        val result = db.prepare(
            "SELECT id, title, provider, model, created_at, updated_at FROM sessions " +
                "ORDER BY updated_at DESC LIMIT 200",
        ).all()
        return rawJson(result.results)
    }
    if (path == "/api/sessions" && method == "POST") {
        val body = jsonBody(request)
        val requested = body.id?.toString()?.trim().orEmpty()
        val id = if (requested.isNotEmpty()) requested else "s" + (js("Date.now")() as Double).toLong().toString(36)
        if (!KEY_RE.containsMatchIn(id)) return json(dynamic { error = "bad id" }, 400)
        db.prepare(
            "INSERT OR IGNORE INTO sessions(id, title, model, created_at, updated_at) " +
                "VALUES(?, ?, ?, unixepoch(), unixepoch())",
        ).bind(id, body.title?.toString() ?: id, body.model?.toString() ?: "").run()
        val row = db.prepare(
            "SELECT id, title, provider, model, created_at, updated_at FROM sessions WHERE id = ?",
        ).bind(id).first()
        return rawJson(row ?: dynamic { this.id = id })
    }

    val oneSession = matchPath(path, "/api/sessions/")
    if (oneSession != null && !oneSession.endsWith("/turns")) {
        val sid = decodeURIComponent(oneSession)
        if (method == "PATCH") {
            val title = (jsonBody(request).title?.toString() ?: "").trim().take(120)
            if (title.isEmpty()) return json(dynamic { error = "title required" }, 400)
            val info = db.prepare("UPDATE sessions SET title = ?, updated_at = unixepoch() WHERE id = ?")
                .bind(title, sid).run()
            if (!changed(info)) return json(dynamic { error = "not_found" }, 404)
            return json(dynamic { ok = true; this.id = sid; this.title = title })
        }
        if (method == "DELETE") {
            db.prepare("DELETE FROM turns WHERE session_id = ?").bind(sid).run()
            db.prepare("DELETE FROM state WHERE key = ?").bind("sessions/$sid").run()
            val info = db.prepare("DELETE FROM sessions WHERE id = ?").bind(sid).run()
            if (!changed(info)) return json(dynamic { error = "not_found" }, 404)
            return json(dynamic { ok = true; this.id = sid })
        }
    }
    val turns = oneSession?.takeIf { it.endsWith("/turns") }?.removeSuffix("/turns")
    if (turns != null) {
        val sid = decodeURIComponent(turns)
        if (method == "GET") {
            val before = url.searchParams.get("before_seq")?.toDoubleOrNull() ?: 9.007199254740991E15
            val limit = ((url.searchParams.get("limit")?.toDoubleOrNull() ?: 50.0).coerceAtMost(200.0))
            val result = db.prepare(
                "SELECT seq, role, agent, job_id, text, created_at FROM turns " +
                    "WHERE session_id = ? AND seq < ? ORDER BY seq DESC LIMIT ?",
            ).bind(sid, before.toLong(), limit.toLong()).all()
            val rows = (result.results as Array<dynamic>).reversed()
            return rawJson(dynamic { this.turns = rows })
        }
        if (method == "POST") {
            val body = jsonBody(request)
            val role: String = body.role?.toString() ?: "model"
            db.prepare("INSERT OR IGNORE INTO sessions(id, title, updated_at) VALUES(?, ?, unixepoch())")
                .bind(sid, sid).run()
            if (role == "user") {
                val titled = db.prepare("SELECT title FROM sessions WHERE id = ?").bind(sid).first()
                val current: String = titled?.title?.toString() ?: ""
                if (titled != null && (current.isEmpty() || current == sid)) {
                    val text = (body.text?.toString() ?: "").take(42)
                    db.prepare("UPDATE sessions SET title = ? WHERE id = ?")
                        .bind(if (text.isNotEmpty()) text else sid, sid).run()
                }
            }
            val max = db.prepare("SELECT COALESCE(MAX(seq),0) AS m FROM turns WHERE session_id = ?")
                .bind(sid).first()
            val seq = ((max?.m?.toDoubleOrNull() ?: 0.0) + 1).toLong()
            db.prepare("INSERT INTO turns(session_id, seq, role, agent, job_id, text) VALUES(?,?,?,?,?,?)")
                .bind(sid, seq, role, body.agent?.toString() ?: "", body.job_id?.toString() ?: "", body.text?.toString() ?: "")
                .run()
            db.prepare("UPDATE sessions SET updated_at = unixepoch() WHERE id = ?").bind(sid).run()
            return json(dynamic { this.seq = seq })
        }
    }
    return json(dynamic { error = "not_found" }, 404)
}

private val KEY_RE = Regex("^[A-Za-z0-9:_-]{1,128}$")
private val PREFIX_RE = Regex("^[A-Za-z0-9:_/-]*$")
private val TUNNEL_RE = Regex("^https://[A-Za-z0-9.-]+\\.trycloudflare\\.com$")

/** The one path segment after a prefix, or null when the path does not match. */
private fun matchPath(path: String, prefix: String): String? {
    if (!path.startsWith(prefix)) return null
    val rest = path.removePrefix(prefix)
    if (rest.isEmpty() || rest.contains('/') && rest.count { it == '/' } > 1) return null
    if (rest.count { it == '/' } > 1) return null
    return rest
}

private suspend fun jsonBody(request: dynamic): dynamic =
    runCatching { request.json() }.getOrNull() ?: dynamic {}

private fun valueOf(body: dynamic): String {
    val value = body.value
    if (value == null || value == undefined) return "null"
    if (jsTypeOf(value) == "string") return value.toString()
    return JSON.stringify(value)
}

private fun changed(info: dynamic): Boolean =
    (info?.meta?.changes?.toDoubleOrNull() ?: 0.0) > 0.0

private fun json(value: dynamic, status: Int = 200): dynamic =
    rawJson(value, status)

private fun rawJson(value: dynamic, status: Int = 200): dynamic {
    val body = JSON.stringify(value)
    return js("new Response")(body, dynamic {
        status = status
        headers = dynamic { this["content-type"] = "application/json" }
    })
}

private fun text(body: String, status: Int): dynamic =
    js("new Response")(body, dynamic { this.status = status })
