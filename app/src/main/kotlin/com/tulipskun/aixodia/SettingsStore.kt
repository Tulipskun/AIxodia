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
    private val wsUrl = stringPreferencesKey("ws_url")
    private val workerUrl = stringPreferencesKey("worker_url")
    private val token = stringPreferencesKey("token")
    private val sessionId = stringPreferencesKey("session_id")

    val wsUrlFlow: Flow<String> = ctx.ds.data.map { it[wsUrl] ?: "" }
    val workerUrlFlow: Flow<String> = ctx.ds.data.map { it[workerUrl] ?: "" }
    val tokenFlow: Flow<String> = ctx.ds.data.map { it[token] ?: "" }
    val sessionFlow: Flow<String> = ctx.ds.data.map { it[sessionId] ?: "" }

    suspend fun current(): ConnConfig = ConnConfig(
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

    /** True when both endpoints + token are set (i.e. the app may connect). */
    suspend fun isConfigured(): Boolean {
        val c = current()
        return c.workerUrl.isNotBlank() && c.token.isNotBlank() && c.wsUrl.isNotBlank()
    }
}

data class ConnConfig(val wsUrl: String, val workerUrl: String, val token: String, val sessionId: String)
