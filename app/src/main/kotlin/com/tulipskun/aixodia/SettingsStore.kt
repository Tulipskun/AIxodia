package com.tulipskun.aixodia

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.ds by preferencesDataStore("aixodia")

const val DEFAULT_DAEMON_URL = "https://aixodia.dpdns.org"

/**
 * The Engine URL defaults to the stable Named Tunnel hostname. GitHub
 * Authorization Code + PKCE verifies the account in the system browser; only
 * Engine-issued access/refresh credentials are sealed with Android Keystore.
 * No GitHub OAuth token or Cloudflare token is stored here.
 */
class SettingsStore(private val ctx: Context) {
    private val endpoint = stringPreferencesKey("endpoint")
    private val daemonUrl = stringPreferencesKey("daemon_url")
    // Opaque Engine-issued session token, protected by Android Keystore.
    private val sessionToken = stringPreferencesKey("session_token")
    private val sessionExpiresAt = stringPreferencesKey("session_expires_at")
    private val refreshToken = stringPreferencesKey("refresh_token")
    private val refreshExpiresAt = stringPreferencesKey("refresh_expires_at")
    private val legacyGithubToken = stringPreferencesKey("github_token")
    private val sessionId = stringPreferencesKey("session_id")
    private val terminalSessionId = stringPreferencesKey("terminal_session_id")
    private val terminalSessionIds = stringPreferencesKey("terminal_session_ids")
    // id -> pid of each live shell, so a tab can say which process it is and a
    // reconnect can show the same identity before the engine's ready frame.
    private val terminalSessionPids = stringPreferencesKey("terminal_session_pids")
    // Tabs the user closed that the engine still lists. A close is a DELETE the
    // engine may not hear (it is exactly when it is restarting), and the shell
    // survives that in tmux; this is what makes the retry — and the fact that
    // the tab must stay gone — outlive the process.
    private val terminalClosedIds = stringPreferencesKey("terminal_closed_ids")
    // The daemon's start time, kept so the header's uptime counter has a number
    // the moment the app opens — before the Engine health check confirms it.
    private val daemonStartedAt = stringPreferencesKey("daemon_started_at")
    // When the machine the daemon runs on booted. The counter shows this rather
    // than the process start, because an engine update restarts the process and
    // the box has stayed up through it.
    private val daemonBootAt = stringPreferencesKey("daemon_boot_at")
    // Which counts the footer shows, in order. A stored comma-separated list of
    // TokenField names, because providers differ: Anthropic reports no
    // reasoning count, so a reasoning field there would print a zero that means
    // "this provider does not say" rather than "the model did not think".
    private val tokenFields = stringPreferencesKey("token_fields")
    private val tokenFieldsByProvider =
        stringPreferencesKey("token_fields_by_provider")
    private val providersCache = stringPreferencesKey("providers_cache")
    // The tunnel that produced providers_cache. A new engine (or a different
    // account behind the same app) must not inherit the old provider list.
    private val providersCacheUrl = stringPreferencesKey("providers_cache_url")

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

    val endpointFlow: Flow<String> = ctx.ds.data.map { it[endpoint]?.takeIf(String::isNotBlank) ?: DEFAULT_DAEMON_URL }
    val daemonUrlFlow: Flow<String> = ctx.ds.data.map { it[daemonUrl]?.takeIf(String::isNotBlank) ?: DEFAULT_DAEMON_URL }
    val sessionFlow: Flow<String> = ctx.ds.data.map { it[sessionId] ?: "" }
    /** The cached daemon start time (unix second), 0 when never seen. */
    val terminalSessionIdFlow: Flow<String> = ctx.ds.data.map { it[terminalSessionId] ?: "" }
    val terminalSessionIdsFlow: Flow<String> = ctx.ds.data.map { it[terminalSessionIds] ?: "" }
    val terminalClosedIdsFlow: Flow<Set<String>> = ctx.ds.data.map {
        (it[terminalClosedIds] ?: "").split(",").map(String::trim).filter(String::isNotBlank).toSet()
    }
    val terminalSessionPidsFlow: Flow<Map<String, Int>> = ctx.ds.data.map {
        parseSessionPids(it[terminalSessionPids].orEmpty())
    }
    val daemonStartedAtFlow: Flow<Long> =
        ctx.ds.data.map { it[daemonStartedAt]?.toLongOrNull() ?: 0L }
    val daemonBootAtFlow: Flow<Long> =
        ctx.ds.data.map { it[daemonBootAt]?.toLongOrNull() ?: 0L }

    suspend fun current(): ConnConfig {
        val prefs = ctx.ds.data.first()
        val now = System.currentTimeMillis() / 1000
        val accessExpires = prefs[sessionExpiresAt]?.toLongOrNull() ?: 0L
        val refreshExpires = prefs[refreshExpiresAt]?.toLongOrNull() ?: 0L
        val rawAccess = prefs[sessionToken].orEmpty()
        val rawRefresh = prefs[refreshToken].orEmpty()
        val accessInDate = accessExpires > now
        val refreshInDate = refreshExpires > now
        val accessToken = if (accessInDate) TokenCipher.open(rawAccess) else ""
        val storedRefresh = if (refreshInDate) TokenCipher.open(rawRefresh) else ""
        val unreadableAccess = accessInDate && rawAccess.isNotBlank() && accessToken.isBlank()
        val unreadableRefresh = refreshInDate && rawRefresh.isNotBlank() && storedRefresh.isBlank()
        val clearExpiredRefresh = !refreshInDate && rawRefresh.isNotBlank()
        val clearLegacyGithubCredential = !prefs[legacyGithubToken].isNullOrBlank()
        if (unreadableAccess || unreadableRefresh || clearExpiredRefresh || clearLegacyGithubCredential) {
            ctx.ds.edit {
                if (unreadableAccess || unreadableRefresh) {
                    it.remove(sessionToken)
                    it.remove(sessionExpiresAt)
                    it.remove(refreshToken)
                    it.remove(refreshExpiresAt)
                } else if (clearExpiredRefresh) {
                    it.remove(refreshToken)
                    it.remove(refreshExpiresAt)
                    // Keep a still-valid access token until its own expiry.
                    if (accessExpires <= now) {
                        it.remove(sessionToken)
                        it.remove(sessionExpiresAt)
                    }
                }
                it.remove(legacyGithubToken)
            }
        }
        return ConnConfig(
            endpoint = prefs[endpoint]?.takeIf(String::isNotBlank) ?: DEFAULT_DAEMON_URL,
            daemonUrl = prefs[daemonUrl]?.takeIf(String::isNotBlank) ?: DEFAULT_DAEMON_URL,
            token = "",
            sessionToken = accessToken,
            sessionId = prefs[sessionId].orEmpty(),
            sessionExpiresAt = accessExpires,
            refreshToken = storedRefresh,
            refreshExpiresAt = if (refreshInDate) refreshExpires else 0L,
        )
    }

    /** Persist Engine credentials only; both secrets are encrypted by Android Keystore. */
    suspend fun saveSessionCredentials(
        accessToken: String,
        accessExpiresAt: Long,
        refreshCredential: String,
        refreshCredentialExpiresAt: Long,
    ) {
        ctx.ds.edit { prefs ->
            prefs.remove(legacyGithubToken)
            if (accessToken.isBlank() || accessExpiresAt <= System.currentTimeMillis() / 1000) {
                prefs.remove(sessionToken)
                prefs.remove(sessionExpiresAt)
            } else {
                prefs[sessionToken] = TokenCipher.seal(accessToken.trim())
                prefs[sessionExpiresAt] = accessExpiresAt.toString()
            }
            if (refreshCredential.isBlank() || refreshCredentialExpiresAt <= System.currentTimeMillis() / 1000) {
                prefs.remove(refreshToken)
                prefs.remove(refreshExpiresAt)
            } else {
                prefs[refreshToken] = TokenCipher.seal(refreshCredential.trim())
                prefs[refreshExpiresAt] = refreshCredentialExpiresAt.toString()
            }
            prefs.remove(stringPreferencesKey("token"))
            prefs.remove(stringPreferencesKey("account_id"))
            prefs.remove(stringPreferencesKey("database_id"))
        }
    }

    /** Compatibility wrapper for sign-out and older callers. */
    suspend fun saveSessionToken(value: String, expiresAt: Long) =
        saveSessionCredentials(value, expiresAt, "", 0L)

    val isSignedInFlow: Flow<Boolean> = ctx.ds.data.map { prefs ->
        val now = System.currentTimeMillis() / 1000
        val accessValid = (prefs[sessionExpiresAt]?.toLongOrNull() ?: 0L) > now
            && TokenCipher.open(prefs[sessionToken].orEmpty()).isNotBlank()
        val refreshValid = (prefs[refreshExpiresAt]?.toLongOrNull() ?: 0L) > now
            && TokenCipher.open(prefs[refreshToken].orEmpty()).isNotBlank()
        accessValid || refreshValid
    }

    suspend fun saveProvidersCache(daemonUrl: String, json: String) {
        ctx.ds.edit {
            it[providersCache] = json
            it[providersCacheUrl] = daemonUrl.trim().trimEnd('/')
        }
    }

    /** The cached provider JSON and the daemon URL that produced it. */
    suspend fun providersCacheSnapshot(): Pair<String, String> {
        val prefs = ctx.ds.data.first()
        return (prefs[providersCache] ?: "") to (prefs[providersCacheUrl] ?: "")
    }

    /**
     * The daemon address: one https tunnel URL. Both legs follow from it — the
     * live socket and the provider/model API the running router owns.
     */
    suspend fun saveDaemon(address: String) {
        val clean = address.trim().trimEnd('/').ifBlank { DEFAULT_DAEMON_URL }
        ctx.ds.edit {
            if ((it[daemonUrl] ?: "").trim().trimEnd('/') != clean) {
                // The provider list belongs to the engine at that address.
                it.remove(providersCache)
                it.remove(providersCacheUrl)
            }
            it[endpoint] = clean
            it[daemonUrl] = clean
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

    /** Tabs the user closed that the engine has not stopped listing yet. */
    suspend fun currentTerminalClosedIds(): Set<String> = terminalClosedIdsFlow.first()

    suspend fun addTerminalClosedId(id: String) {
        val clean = id.trim()
        if (clean.isEmpty()) return
        ctx.ds.edit { prefs ->
            val ids = (prefs[terminalClosedIds] ?: "").split(",").map { it.trim() }.filter { it.isNotBlank() }
            if (clean !in ids) prefs[terminalClosedIds] = (ids + clean).joinToString(",")
        }
    }

    /** Replaces the set once the engine agrees those shells are gone. */
    suspend fun saveTerminalClosedIds(ids: Collection<String>) {
        ctx.ds.edit { it[terminalClosedIds] = ids.filter(String::isNotBlank).joinToString(",") }
    }

    /**
     * Remembers the two clocks the header can count from, for the next open to
     * have a number before the D1 query lands. A non-positive time is the daemon
     * not saying, and is not worth a write.
     */
    suspend fun saveDaemonClocks(startedAt: Long, bootAt: Long) {
        if (startedAt <= 0L && bootAt <= 0L) return
        ctx.ds.edit {
            if (startedAt > 0L) it[daemonStartedAt] = startedAt.toString()
            if (bootAt > 0L) it[daemonBootAt] = bootAt.toString()
        }
    }

    /** True while either the access token or its refresh credential is valid. */
    suspend fun isConfigured(): Boolean = isSignedInFlow.first()


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
    val daemonUrl: String,
    val token: String,
    val accountId: String = "",
    val databaseId: String = "",
    val sessionId: String,
    /** Opaque Engine-issued session token; blank until the user signs in. */
    val sessionToken: String = "",
    val sessionExpiresAt: Long = 0L,
    val refreshToken: String = "",
    val refreshExpiresAt: Long = 0L,
)
