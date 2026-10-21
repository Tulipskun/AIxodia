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
 * There is exactly one credential: the Cloudflare API token, which
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
    private val terminalSessionId = stringPreferencesKey("terminal_session_id")
    private val terminalSessionIds = stringPreferencesKey("terminal_session_ids")
    // id -> pid of each live shell, so a tab can say which process it is and a
    // reconnect can show the same identity before the engine's ready frame.
    private val terminalSessionPids = stringPreferencesKey("terminal_session_pids")
    // The daemon's start time, kept so the header's uptime counter has a number
    // the moment the app opens — the D1 query that confirms it has not landed yet.
    private val daemonStartedAt = stringPreferencesKey("daemon_started_at")
    // Which counts the footer shows, in order. A stored comma-separated list of
    // TokenField names, because providers differ: Anthropic reports no
    // reasoning count, so a reasoning field there would print a zero that means
    // "this provider does not say" rather than "the model did not think".
    private val tokenFields = stringPreferencesKey("token_fields")
    private val tokenFieldsByProvider =
        stringPreferencesKey("token_fields_by_provider")
    private val providersCache = stringPreferencesKey("providers_cache")

    /** The footer format for a provider, falling back to the shared default. */
    fun tokenFieldsFlow(provider: String) = ctx.ds.data.map { prefs ->
        val byProvider = prefs[tokenFieldsByProvider].orEmpty()
            .split(';')
            .firstOrNull { it.substringBefore('=') == provider }
            ?.substringAfter('=')
        val spec = byProvider?.takeIf { it.isNotBlank() } ?: prefs[tokenFields]
        com.tulipskun.aixodia.ui.display.TokenField.parse(spec.orEmpty())
    }

    suspend fun setTokenFields(spec: String) {
        ctx.ds.edit { it[tokenFields] = spec }
    }

    /** Pins a field list to one provider, leaving the shared one untouched. */
    suspend fun setTokenFieldsForProvider(provider: String, spec: String) {
        ctx.ds.edit { prefs ->
            val kept = prefs[tokenFieldsByProvider].orEmpty()
                .split(';')
                .filter { it.isNotBlank() && it.substringBefore('=') != provider }
            val entry = "$provider=$spec"
            prefs[tokenFieldsByProvider] = (kept + entry).joinToString(";")
        }
    }

    val endpointFlow: Flow<String> = ctx.ds.data.map { it[endpoint] ?: "" }
    val wsUrlFlow: Flow<String> = ctx.ds.data.map { it[wsUrl] ?: "" }
    val daemonUrlFlow: Flow<String> = ctx.ds.data.map { it[daemonUrl] ?: "" }
    val tokenFlow: Flow<String> = ctx.ds.data.map { it[token] ?: "" }
    val accountIdFlow: Flow<String> = ctx.ds.data.map { it[accountId] ?: "" }
    val databaseIdFlow: Flow<String> = ctx.ds.data.map { it[databaseId] ?: "" }
    val sessionFlow: Flow<String> = ctx.ds.data.map { it[sessionId] ?: "" }
    val providersCacheFlow: Flow<String> = ctx.ds.data.map { it[providersCache] ?: "" }

    /** The cached daemon start time (unix second), 0 when never seen. */
    val terminalSessionIdFlow: Flow<String> = ctx.ds.data.map { it[terminalSessionId] ?: "" }
    val terminalSessionIdsFlow: Flow<String> = ctx.ds.data.map { it[terminalSessionIds] ?: "" }
    val terminalSessionPidsFlow: Flow<Map<String, Int>> = ctx.ds.data.map {
        parseSessionPids(it[terminalSessionPids].orEmpty())
    }
    val daemonStartedAtFlow: Flow<Long> =
        ctx.ds.data.map { it[daemonStartedAt]?.toLongOrNull() ?: 0L }

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
     * back here, so nobody has to type a Cloudflare id.
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

    suspend fun saveProvidersCache(json: String) {
        ctx.ds.edit { it[providersCache] = json }
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
     * Remembers when the daemon started, for the next open to count from. A
     * non-positive time is the daemon not saying, and is not worth a write.
     */
    suspend fun currentTerminalSessionId(): String = terminalSessionIdFlow.first()
    suspend fun currentTerminalSessionIds(): List<String> =
        terminalSessionIdsFlow.first().split(",").map { it.trim() }.filter { it.isNotBlank() }
    suspend fun currentTerminalSessionPids(): Map<String, Int> = terminalSessionPidsFlow.first()

    suspend fun saveTerminalSessionId(id: String) {
        ctx.ds.edit { it[terminalSessionId] = id.trim() }
    }

    suspend fun saveTerminalSessionIds(ids: List<String>) {
        ctx.ds.edit { it[terminalSessionIds] = ids.joinToString(",") }
    }

    /** Replaces the whole id -> pid map, as the engine's live list says. */
    suspend fun saveTerminalSessionPids(pids: Map<String, Int>) {
        ctx.ds.edit { it[terminalSessionPids] = formatSessionPids(pids) }
    }

    /** Remembers the pid the engine named for one shell. */
    suspend fun saveTerminalSessionPid(id: String, pid: Int) {
        val clean = id.trim()
        if (clean.isEmpty() || pid <= 0) return
        ctx.ds.edit { prefs ->
            val pids = parseSessionPids(prefs[terminalSessionPids].orEmpty()).toMutableMap()
            pids[clean] = pid
            prefs[terminalSessionPids] = formatSessionPids(pids)
        }
    }

    /**
     * Adds one known shell id in a single DataStore edit. Two ready frames can
     * land together (a new shell and a reattach), and the old read-modify-write
     * let one of them overwrite the other's tab.
     */
    suspend fun addTerminalSessionId(id: String) {
        val clean = id.trim()
        if (clean.isEmpty()) return
        ctx.ds.edit { prefs ->
            val ids = (prefs[terminalSessionIds] ?: "").split(",").map { it.trim() }.filter { it.isNotBlank() }
            if (clean !in ids) prefs[terminalSessionIds] = (ids + clean).joinToString(",")
        }
    }

    /** Drops one shell id and clears "current" only when it was that shell. */
    suspend fun removeTerminalSessionId(id: String) {
        val clean = id.trim()
        if (clean.isEmpty()) return
        ctx.ds.edit { prefs ->
            val ids = (prefs[terminalSessionIds] ?: "").split(",").map { it.trim() }.filter { it.isNotBlank() && it != clean }
            prefs[terminalSessionIds] = ids.joinToString(",")
            val pids = parseSessionPids(prefs[terminalSessionPids].orEmpty()).toMutableMap()
            pids.remove(clean)
            prefs[terminalSessionPids] = formatSessionPids(pids)
            if ((prefs[terminalSessionId] ?: "").trim() == clean) prefs.remove(terminalSessionId)
        }
    }

    suspend fun clearTerminalSessionId() {
        ctx.ds.edit { it.remove(terminalSessionId) }
    }

    /**
     * Remembers when the daemon started, for the next open to count from. A
     * non-positive time is the daemon not saying, and is not worth a write.
     */
    suspend fun saveDaemonStartedAt(startedAt: Long) {
        if (startedAt <= 0L) return
        ctx.ds.edit { it[daemonStartedAt] = startedAt.toString() }
    }

    /**
     * True once a Cloudflare API token is set. History needs nothing else, so a
     * phone with no daemon address still opens and edits its chats;
     * only the live socket stays offline until an address is known.
     */
    suspend fun isConfigured(): Boolean = current().token.isNotBlank()

    companion object {
        /**
         * https://host/room/name -> wss://host/room/name
         *
         * The path is the room and it must survive: the relay pairs the engine
         * and the phone inside that one Durable Object, so dropping the path
         * would dial the worker's root and pair with nothing.
         */
        fun wsUrlFor(address: String): String {
            val clean = address.trim().trimEnd('/')
            if (clean.isEmpty()) return ""
            val scheme = when {
                clean.startsWith("https://") -> "wss"
                clean.startsWith("http://") -> "ws"
                else -> return ""
            }
            return "$scheme://" + clean.substringAfter("://")
        }
    }
}

/**
 * "t-1=4242,t-2=4243" -> {t-1: 4242, t-2: 4243}. Kept as one preference so a
 * whole engine list lands in a single DataStore edit.
 */
private fun parseSessionPids(raw: String): Map<String, Int> {
    if (raw.isBlank()) return emptyMap()
    val out = LinkedHashMap<String, Int>()
    for (entry in raw.split(',')) {
        val at = entry.lastIndexOf('=')
        if (at <= 0) continue
        val id = entry.substring(0, at).trim()
        val pid = entry.substring(at + 1).trim().toIntOrNull() ?: continue
        if (id.isNotEmpty() && pid > 0) out[id] = pid
    }
    return out
}

private fun formatSessionPids(pids: Map<String, Int>): String =
    pids.entries
        .filter { it.key.isNotBlank() && it.value > 0 }
        .joinToString(",") { "${it.key}=${it.value}" }

data class ConnConfig(
    val endpoint: String,
    val wsUrl: String,
    val daemonUrl: String,
    val token: String,
    val accountId: String,
    val databaseId: String,
    val sessionId: String,
)
