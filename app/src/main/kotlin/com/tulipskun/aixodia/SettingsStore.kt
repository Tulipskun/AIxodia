package com.tulipskun.aixodia

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.ds by preferencesDataStore("aixodia")

class SettingsStore(private val ctx: Context) {
    private val wsUrl = stringPreferencesKey("ws_url")
    private val workerUrl = stringPreferencesKey("worker_url")
    private val token = stringPreferencesKey("token")
    private val sessionId = stringPreferencesKey("session_id")

    // Defaults point at the local mock stack (mock/cmd/mockai) so the app is
    // testable before Cloudflare exists. 10.0.2.2 is the Android emulator's
    // alias for the host machine; on a real phone use the host's LAN IP.
    val wsUrlFlow: Flow<String> = ctx.ds.data.map { it[wsUrl] ?: "ws://10.0.2.2:39118/ws" }
    val workerUrlFlow: Flow<String> = ctx.ds.data.map { it[workerUrl] ?: "http://10.0.2.2:39117" }
    val tokenFlow: Flow<String> = ctx.ds.data.map { it[token] ?: "devtoken" }
    val sessionFlow: Flow<String> = ctx.ds.data.map { it[sessionId] ?: "work-1" }

    suspend fun current(): ConnConfig = ConnConfig(
        wsUrl = wsUrlFlow.first(), workerUrl = workerUrlFlow.first(),
        token = tokenFlow.first(), sessionId = sessionFlow.first(),
    )

    suspend fun save(ws: String, worker: String, tok: String, sess: String) {
        ctx.ds.edit {
            it[wsUrl] = ws
            it[workerUrl] = worker
            it[token] = tok
            it[sessionId] = sess.ifBlank { "default" }
        }
    }

    /** Switch session only — never touches connection settings. */
    suspend fun saveSession(sess: String) {
        ctx.ds.edit { it[sessionId] = sess.ifBlank { "default" } }
    }

    /** Save connection fields; blank inputs keep the previous value. */
    suspend fun saveConnection(ws: String, worker: String, tok: String) {
        ctx.ds.edit {
            if (ws.isNotBlank()) it[wsUrl] = ws.trim()
            if (worker.isNotBlank()) it[workerUrl] = worker.trim().trimEnd('/')
            it[token] = tok // token may be intentionally cleared
        }
    }
}

data class ConnConfig(val wsUrl: String, val workerUrl: String, val token: String, val sessionId: String)
