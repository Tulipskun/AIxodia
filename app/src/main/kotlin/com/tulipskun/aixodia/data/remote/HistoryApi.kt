package com.tulipskun.aixodia.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import org.json.JSONArray
import org.json.JSONObject
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.tulipskun.aixodia.SettingsStore
import com.tulipskun.aixodia.data.model.AgentSettings
import com.tulipskun.aixodia.data.model.SessionAgentConfig
import com.tulipskun.aixodia.data.model.ModelsPage
import com.tulipskun.aixodia.data.model.ProvidersPage
import com.tulipskun.aixodia.data.model.ProviderStatus
import com.tulipskun.aixodia.data.model.ProviderView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The app's two back ends in one place (D-011).
 *
 *  - **Cloudflare D1** through `D1Api` for history — the chat list, paging and
 *    session-level writes — so a stopped daemon does not hide the history;
 *  - **the daemon over its tunnel** for the provider catalogue, provider
 *    health, per-chat model pins and the agent settings, because validating
 *    those needs the running router rather than the database.
 */
@JsonClass(generateAdapter = true)
data class TurnRow(
    @Json(name = "seq") val seq: Long = 0,
    @Json(name = "role") val role: String = "",
    @Json(name = "agent") val agent: String = "",
    @Json(name = "job_id") val jobId: String = "",
    @Json(name = "text") val text: String = "",
    @Json(name = "created_at") val createdAt: Long = 0,
    @Json(name = "model") val model: String = "",
    @Json(name = "input_tokens") val inputTokens: Int = 0,
    @Json(name = "output_tokens") val outputTokens: Int = 0,
    @Json(name = "cache_read_tokens") val cacheRead: Int = 0,
    @Json(name = "cache_write_tokens") val cacheWrite: Int = 0,
    @Json(name = "duration_ms") val durationMs: Long = 0,
)


@JsonClass(generateAdapter = true)
data class NodeInfo(
    @Json(name = "tunnel_url") val tunnelUrl: String = "",
    @Json(name = "version") val version: String = "",
    @Json(name = "online") val online: Boolean = false,
    @Json(name = "heartbeat_age_s") val ageS: Long = -1,
)

@JsonClass(generateAdapter = true)
data class SessionRow(
    @Json(name = "id") val id: String = "",
    @Json(name = "title") val title: String = "",
    @Json(name = "model") val model: String = "",
    @Json(name = "provider") val provider: String = "",
    @Json(name = "updated_at") val updatedAt: Long = 0,
)

class HistoryApi(private val settings: SettingsStore) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    // History is Cloudflare D1 read directly (D-011); everything in this class
    // that still goes over HTTP talks to the daemon through its tunnel.
    private val d1 = D1Api(settings)
    // The settings calls are not all instant: asking the daemon to re-check its
    // providers means waiting for the gateways to answer, through a tunnel that
    // can take seconds per round trip. The default 10s read timeout cut those
    // calls off halfway and left the screen with nothing to show.
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()
    private val modelsAdapter = moshi.adapter(ModelsPage::class.java)
    private val providersAdapter = moshi.adapter(ProvidersPage::class.java)
    private val providerAdapter = moshi.adapter(ProviderStatus::class.java)
    private val settingsAdapter = moshi.adapter(AgentSettings::class.java)
    private val sessionAgentAdapter = moshi.adapter(SessionAgentConfig::class.java)

    /**
     * Builds an absolute URL, or null when the app has no endpoint configured
     * yet. A fresh install has empty settings by design, and that must look
     * like "not configured", never like a crash: okhttp throws on a relative
     * URL, and that exception used to escape into the ViewModel scope.
     */
    private fun absoluteUrl(raw: String): String? {
        val base = raw.trim().trimEnd('/')
        if (base.isEmpty()) return null
        if (!base.startsWith("http://") && !base.startsWith("https://")) return null
        return base
    }

    /**
     * Every chat D1 knows, newest first (AX-010, AX-011). The phone reads D1
     * itself now, so the list still arrives with the daemon stopped.
     */
    suspend fun sessions(): List<SessionRow> = withContext(Dispatchers.IO) {
        runCatching { d1.sessions() }.getOrDefault(emptyList())
    }

    /** Creates the chat in D1 too, so the daemon and phone agree. */
    suspend fun createSession(id: String, title: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { d1.createSession(id, title) }.getOrDefault(false)
    }

    /** Renames a chat, the way Gemini/ChatGPT let you retitle a conversation. */
    suspend fun renameSession(sessionId: String, title: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { d1.renameSession(sessionId, title) }.getOrDefault(false)
    }

    /** Deletes a chat with its history, in D1 (AX-014). */
    suspend fun deleteSession(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { d1.deleteSession(sessionId) }.getOrDefault(false)
    }

    /** Newest page for a session; the app calls this on open and on reconnect. */
    suspend fun latest(sessionId: String, limit: Int = 200): List<TurnRow> = turns(sessionId, 0, limit)

    /** One page of history, oldest-first, read straight from D1 (AX-011). */
    suspend fun turns(sessionId: String, beforeSeq: Long, limit: Int = 50): List<TurnRow> =
        withContext(Dispatchers.IO) {
            runCatching { d1.turns(sessionId, beforeSeq, limit) }.getOrDefault(emptyList())
        }

    /**
     * The providers and models the daemon can route to right now, so the app
     * never has to hardcode a model list or a provider name.
     */
    suspend fun models(): List<ProviderView> = withContext(Dispatchers.IO) {
        val c = settings.current()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext emptyList()
        runCatching {
            val req = Request.Builder().url("$base/api/models")
                .header("Authorization", "Bearer ${c.token}").get().build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use emptyList()
                modelsAdapter.fromJson(r.body!!.source())?.providers ?: emptyList()
            }
        }.getOrDefault(emptyList())
    }

    /** Pins the provider and model for one chat; the daemon keeps it for good. */
    suspend fun setSessionModel(sessionId: String, provider: String, model: String): Boolean = withContext(Dispatchers.IO) {
        val c = settings.current()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext false
        val body = JSONObject().put("provider", provider).put("model", model).toString()
        runCatching {
            val req = Request.Builder().url("$base/api/sessions/$sessionId")
                .header("Authorization", "Bearer ${c.token}")
                .header("Content-Type", "application/json")
                .patch(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /**
     * Removes the pin and returns the chat to the global agent defaults. This is
     * a separate explicit request because an empty provider/model PATCH also
     * means "only rename this chat", never "unpin it".
     */
    suspend fun clearSessionModel(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        val c = settings.current()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext false
        val body = JSONObject().put("clear_model", true).toString()
        runCatching {
            val req = Request.Builder().url("$base/api/sessions/$sessionId")
                .header("Authorization", "Bearer ${c.token}")
                .header("Content-Type", "application/json")
                .patch(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** Every configured provider with its reachability and last error. */
    suspend fun providers(): List<ProviderStatus> = withContext(Dispatchers.IO) {
        val base = absoluteUrl(settings.current().daemonUrl) ?: return@withContext emptyList()
        runCatching {
            val req = Request.Builder().url("$base/api/providers")
                .header("Authorization", "Bearer ${settings.current().token}").get().build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use emptyList()
                providersAdapter.fromJson(r.body!!.source())?.providers ?: emptyList()
            }
        }.getOrDefault(emptyList())
    }

    /** Asks the daemon to re-run model discovery and report what is reachable. */
    suspend fun refreshProviders(): List<ProviderStatus> = withContext(Dispatchers.IO) {
        val c = settings.current()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext emptyList()
        runCatching {
            val req = Request.Builder().url("$base/api/providers/refresh")
                .header("Authorization", "Bearer ${c.token}")
                .header("Content-Type", "application/json")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use emptyList()
                providersAdapter.fromJson(r.body!!.source())?.providers ?: emptyList()
            }
        }.getOrDefault(emptyList())
    }

    /** Tests one provider only, so a dead key does not wait behind five others. */
    suspend fun refreshProvider(providerId: String): ProviderStatus? = withContext(Dispatchers.IO) {
        val c = settings.current()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext null
        runCatching {
            val req = Request.Builder().url("$base/api/providers/$providerId/refresh")
                .header("Authorization", "Bearer ${c.token}")
                .header("Content-Type", "application/json")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) null else providerAdapter.fromJson(r.body!!.source())
            }
        }.getOrDefault(null)
    }

    /**
     * The daemon's own reason for a rejected write. "HTTP 400" on its own tells
     * the operator nothing, and the reason is already in the body.
     */
    private fun failure(ok: String, r: okhttp3.Response): String {
        if (r.isSuccessful) return ok
        val reason = runCatching {
            r.body?.string()?.takeIf { it.isNotBlank() }?.let { body ->
                Regex("\"error\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            }
        }.getOrNull()
        return if (reason.isNullOrBlank()) "HTTP ${r.code}" else "HTTP ${r.code}: $reason"
    }

    /** Adds a provider. The key travels once, in this request, and is never read back. */
    suspend fun addProvider(
        id: String, adapter: String, endpoint: String, keys: List<String>, freeOnly: Boolean,
    ): String = withContext(Dispatchers.IO) {
        val c = settings.current()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext "ยังตั้งค่า URL ไม่ครบ"
        val body = JSONObject()
            .put("id", id)
            .put("adapter", adapter)
            .put("endpoint", endpoint)
            .put("free_only", freeOnly)
            .put("keys", JSONArray(keys))
            .toString()
        runCatching {
            val req = Request.Builder().url("$base/api/providers")
                .header("Authorization", "Bearer ${c.token}")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { r ->
                failure("เพิ่ม $id แล้ว", r).let { if (r.isSuccessful) it else "เพิ่มไม่สำเร็จ: $it" }
            }
        }.getOrDefault("เพิ่มไม่สำเร็จ")
    }

    /** Edits one provider's key pool: add, remove by position, or replace wholesale. */
    suspend fun changeKeys(
        providerId: String, add: List<String> = emptyList(), remove: List<Int> = emptyList(), replace: List<String> = emptyList(),
    ): String = withContext(Dispatchers.IO) {
        val c = settings.current()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext "ยังตั้งค่า URL ไม่ครบ"
        // Written by a JSON writer, never by string interpolation: a key with a
        // quote, a backslash or a newline must not corrupt the request.
        val body = JSONObject().apply {
            if (add.isNotEmpty()) put("add", JSONArray(add))
            if (remove.isNotEmpty()) put("remove", JSONArray(remove))
            if (replace.isNotEmpty()) put("replace", JSONArray(replace))
        }.toString()
        runCatching {
            val req = Request.Builder().url("$base/api/providers/$providerId/keys")
                .header("Authorization", "Bearer ${c.token}")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { r ->
                failure("อัปเดต key ของ $providerId แล้ว", r).let { if (r.isSuccessful) it else "อัปเดตไม่สำเร็จ: $it" }
            }
        }.getOrDefault("อัปเดตไม่สำเร็จ")
    }

    suspend fun removeProvider(providerId: String): String = withContext(Dispatchers.IO) {
        val c = settings.current()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext "ยังตั้งค่า URL ไม่ครบ"
        runCatching {
            val req = Request.Builder().url("$base/api/providers/$providerId")
                .header("Authorization", "Bearer ${c.token}").delete().build()
            client.newCall(req).execute().use { r ->
                failure("ลบ $providerId แล้ว", r).let { if (r.isSuccessful) it else "ลบไม่สำเร็จ: $it" }
            }
        }.getOrDefault("ลบไม่สำเร็จ")
    }

    /** Saves which provider and model the main and sub agent run on. */
    suspend fun saveAgentSettings(settingsBody: AgentSettings): String = withContext(Dispatchers.IO) {
        val c = settings.current()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext "ยังตั้งค่า URL ไม่ครบ"
        val body = JSONObject()
            .put("main", JSONObject().put("provider", settingsBody.main.provider).put("model", settingsBody.main.model))
            .put("sub", JSONObject().put("provider", settingsBody.sub.provider).put("model", settingsBody.sub.model))
            .put("sub_enabled", settingsBody.subEnabled)
            .toString()
        runCatching {
            val req = Request.Builder().url("$base/api/settings")
                .header("Authorization", "Bearer ${c.token}")
                .header("Content-Type", "application/json")
                .put(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { r ->
                failure("บันทึกการตั้งค่า agent แล้ว", r).let { if (r.isSuccessful) it else "บันทึกไม่สำเร็จ: $it" }
            }
        }.getOrDefault("บันทึกไม่สำเร็จ")
    }

    suspend fun agentSettings(): AgentSettings? = withContext(Dispatchers.IO) {
        val c = settings.current()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext null
        runCatching {
            val req = Request.Builder().url("$base/api/settings")
                .header("Authorization", "Bearer ${c.token}").get().build()
            client.newCall(req).execute().use { r ->
                if (r.isSuccessful) settingsAdapter.fromJson(r.body!!.source()) else null
            }
        }.getOrNull()
    }

    /** Connectivity check for the Settings screen: session count, or throw. */
    suspend fun ping(): Int = withContext(Dispatchers.IO) { d1.ping() }

    /**
     * Resolves the Cloudflare account and database from the token and reports
     * what was found, so the settings screen can show it and remember it
     * (AX-030). Throws with Cloudflare's own message when it cannot.
     */
    suspend fun discover(): D1Api.Target = withContext(Dispatchers.IO) { d1.discover() }

    /**
     * Tunnel discovery: where the ai daemon is, read from the D1 `nodes` row
     * (AX-050). A failure is reported rather than hidden, so the settings screen
     * can tell "D1 unreachable" from "the daemon has not beaten yet".
     */
    suspend fun node(): NodeInfo? = withContext(Dispatchers.IO) { d1.node() }

    /** https://x.trycloudflare.com -> wss://x.trycloudflare.com/ws */
    fun wsUrlFor(tunnelUrl: String): String =
        tunnelUrl.replaceFirst("https://", "wss://").trimEnd('/') + "/ws"
}

    /** Reads the effective per-session agent config (main + sub, pinned or default). */
    suspend fun sessionAgentConfig(sessionId: String): SessionAgentConfig? = withContext(Dispatchers.IO) {
        val c = settings.current()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext null
        runCatching {
            val req = Request.Builder().url("$base/api/sessions/$sessionId")
                .header("Authorization", "Bearer ${c.token}").get().build()
            client.newCall(req).execute().use { r ->
                if (r.isSuccessful) sessionAgentAdapter.fromJson(r.body!!.source()) else null
            }
        }.getOrNull()
    }

    /** Saves the per-session agent config (main pin + sub-agent override). */
    suspend fun saveSessionAgentConfig(
        sessionId: String,
        mainProvider: String,
        mainModel: String,
        clearMain: Boolean,
        subProvider: String,
        subModel: String,
        subEnabled: Boolean?,
        clearSub: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        val c = settings.current()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext false
        val body = JSONObject()
            .put("provider", mainProvider)
            .put("model", mainModel)
            .put("clear_model", clearMain)
            .put("sub_provider", subProvider)
            .put("sub_model", subModel)
            .put("clear_sub", clearSub)
        if (subEnabled != null) body.put("sub_enabled", subEnabled)
        runCatching {
            val req = Request.Builder().url("$base/api/sessions/$sessionId")
                .header("Authorization", "Bearer ${c.token}")
                .header("Content-Type", "application/json")
                .patch(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { r -> r.isSuccessful }
        }.getOrDefault(false)
    }
