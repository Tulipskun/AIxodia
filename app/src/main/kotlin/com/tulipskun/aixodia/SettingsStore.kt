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
 * repo. Defaults are intentionally empty so an unconfigured app asks instead of
 * silently talking to a wrong endpoint.
 *
 * Since D-011 there is exactly one credential: the Cloudflare API token, which
 * the app uses against the D1 REST API and hands to the daemon in the WebSocket
 * hello. The account and database ids are the token's own answer — discovered
 * and cached, never required from the operator — and the daemon address only
 * carries the live socket and the provider/model API.
 */
class SettingsStore(private val ctx: Context) {
    private val endpoint = stringPreferencesKey("endpoint")
    private val wsUrl = stringPreferencesKey("ws_url")
    private val daemonUrl = stringPreferencesKey("daemon_url")
    private val token = stringPreferencesKey("token")
    private val accountId = stringPreferencesKey("account_id")
    private val databaseId = stringPreferencesKey("database_id")
    private val sessionId = stringPreferencesKey("session_id")

    val endpointFlow: Flow<String> = ctx.ds.data.map { it[endpoint] ?: "" }
    val wsUrlFlow: Flow<String> = ctx.ds.data.map { it[wsUrl] ?: "" }
    val daemonUrlFlow: Flow<String> = ctx.ds.data.map { it[daemonUrl] ?: "" }
    val tokenFlow: Flow<String> = ctx.ds.data.map { it[token] ?: "" }
    val accountIdFlow: Flow<String> = ctx.ds.data.map { it[accountId] ?: "" }
    val databaseIdFlow: Flow<String> = ctx.ds.data.map { it[databaseId] ?: "" }
    val sessionFlow: Flow<String> = ctx.ds.data.map { it[sessionId] ?: "" }

    suspend fun current(): ConnConfig = ConnConfig(
        endpoint = endpointFlow.first(),
        wsUrl = wsUrlFlow.first(),
        daemonUrl = daemonUrlFlow.first(),
        token = tokenFlow.first(),
        accountId = accountIdFlow.first(),
        databaseId = databaseIdFlow.first(),
        sessionId = sessionFlow.first(),
    )

    /**
     * The one credential, plus the ids when they are already known. Blank ids
     * stay blank on purpose: D1Api resolves them from the token and writes them
     * back here, so nobody has to type a Cloudflare id (AX-010, AX-030).
     */
    suspend fun saveD1(tokenValue: String, accountValue: String = "", databaseValue: String = "") {
        ctx.ds.edit {
            it[token] = tokenValue.trim()
            if (accountValue.isNotBlank()) it[accountId] = accountValue.trim()
            if (databaseValue.isNotBlank()) it[databaseId] = databaseValue.trim()
        }
    }

    /** Remembers the ids D1Api resolved, without touching the token. */
    suspend fun saveResolvedTarget(accountValue: String, databaseValue: String) {
        ctx.ds.edit {
            it[accountId] = accountValue
            it[databaseId] = databaseValue
        }
    }

    /**
     * The daemon address: one https tunnel URL. Both legs follow from it — the
     * live socket and the provider/model API the running router owns.
     */
    suspend fun saveDaemon(address: String) {
        val clean = address.trim().trimEnd('/')
        ctx.ds.edit {
            it[endpoint] = clean
            it[daemonUrl] = clean
            it[wsUrl] = wsUrlFor(clean)
        }
    }

    /** Switch session only — never touches connection settings. */
    suspend fun saveSession(sess: String) {
        ctx.ds.edit { it[sessionId] = sess.trim() }
    }

    /**
     * True once a Cloudflare API token is set. History needs nothing else, so a
     * phone with no daemon address still opens and edits its chats (AX-010);
     * only the live socket stays offline until an address is known.
     */
    suspend fun isConfigured(): Boolean = current().token.isNotBlank()

    companion object {
        /** https://x.trycloudflare.com -> wss://x.trycloudflare.com/ws */
        fun wsUrlFor(address: String): String {
            val clean = address.trim().trimEnd('/')
            if (clean.isEmpty()) return ""
            val host = clean.substringAfter("://", clean).substringBefore('/')
            val scheme = if (clean.startsWith("http://")) "ws" else "wss"
            return "$scheme://$host/ws"
        }
    }
}

data class ConnConfig(
    val endpoint: String,
    val wsUrl: String,
    val daemonUrl: String,
    val token: String,
    val accountId: String,
    val databaseId: String,
    val sessionId: String,
)
