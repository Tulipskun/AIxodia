package com.tulipskun.aixodia

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.ds by preferencesDataStore("aixodia")

/**
 * Every connection value is runtime configuration entered by the user in the
 * settings screen — nothing is baked into the app, and no secret ships in the
 * repo. Defaults are intentionally empty so an unconfigured app asks instead
 * of silently talking to a wrong endpoint.
 */
class SettingsStore(private val ctx: Context) {
    private val endpoint = stringPreferencesKey("endpoint")
    private val wsUrl = stringPreferencesKey("ws_url")
    private val workerUrl = stringPreferencesKey("worker_url")
    private val token = stringPreferencesKey("token")
    private val sessionId = stringPreferencesKey("session_id")

    val endpointFlow: Flow<String> = ctx.ds.data.map { it[endpoint] ?: "" }
    val wsUrlFlow: Flow<String> = ctx.ds.data.map { it[wsUrl] ?: "" }
    val workerUrlFlow: Flow<String> = ctx.ds.data.map { it[workerUrl] ?: "" }
    val tokenFlow: Flow<String> = ctx.ds.data.map { it[token] ?: "" }
    val sessionFlow: Flow<String> = ctx.ds.data.map { it[sessionId] ?: "" }

    suspend fun current(): ConnConfig = ConnConfig(
        endpoint = endpointFlow.first(),
        wsUrl = wsUrlFlow.first(), workerUrl = workerUrlFlow.first(),
        token = tokenFlow.first(), sessionId = sessionFlow.first(),
    )

    suspend fun save(ws: String, worker: String, tok: String, sess: String) {
        ctx.ds.edit {
            it[wsUrl] = ws.trim()
            it[workerUrl] = worker.trim().trimEnd('/')
            it[token] = tok.trim()
            it[sessionId] = sess.trim()
        }
    }

    /**
     * The only two things the operator has to supply: one address and the D1
     * token. No account id, no second URL, no flags.
     *
     *  - a trycloudflare URL is the daemon's own tunnel: history is proxied
     *    through it (/api/...) and the live socket is wss://<host>/ws
     *  - a workers.dev (or any other https) URL is the Worker: history goes
     *    direct, and the live socket is discovered from GET /api/node
     */
    suspend fun saveEndpoint(address: String, tok: String, session: String = "") {
        val parts = resolve(address)
        ctx.ds.edit {
            it[endpoint] = address.trim()
            it[wsUrl] = parts.ws
            it[workerUrl] = parts.worker
            it[token] = tok.trim()
            if (session.isNotBlank()) it[sessionId] = session.trim()
        }
    }

    /** Switch session only — never touches connection settings. */
    suspend fun saveSession(sess: String) {
        ctx.ds.edit { it[sessionId] = sess.trim() }
    }

    /** Save connection fields; blank inputs keep the previous value. */
    suspend fun saveConnection(ws: String, worker: String, tok: String) {
        ctx.ds.edit {
            if (ws.isNotBlank()) it[wsUrl] = ws.trim()
            if (worker.isNotBlank()) it[workerUrl] = worker.trim().trimEnd('/')
            it[token] = tok.trim() // token may be intentionally cleared
        }
    }

    /** True when an address + token are set (i.e. the app may connect). */
    suspend fun isConfigured(): Boolean {
        val c = current()
        return c.workerUrl.isNotBlank() && c.token.isNotBlank() && c.wsUrl.isNotBlank()
    }

    companion object {
        /**
         * Turns one pasted address into the two URLs the app actually uses.
         * Exposed as a pure function so the settings screen can preview what a
         * given address will do before saving it.
         */
        fun resolve(address: String): ResolvedEndpoint {
            val clean = address.trim().trimEnd('/')
            if (clean.isEmpty()) return ResolvedEndpoint("", "", EndpointKind.NONE)
            val host = clean.substringAfter("://", clean).substringBefore('/')
            val isTunnel = host.endsWith(".trycloudflare.com")
            val ws = when {
                isTunnel -> "wss://$host/ws"
                clean.startsWith("http://") -> "ws://$host/ws"
                else -> "wss://$host/ws"
            }
            return ResolvedEndpoint(
                ws = ws,
                worker = clean,
                kind = if (isTunnel) EndpointKind.TUNNEL else EndpointKind.WORKER,
            )
        }
    }
}

enum class EndpointKind { NONE, TUNNEL, WORKER }

data class ResolvedEndpoint(val ws: String, val worker: String, val kind: EndpointKind)

data class ConnConfig(
    val endpoint: String,
    val wsUrl: String,
    val workerUrl: String,
    val token: String,
    val sessionId: String,
)
