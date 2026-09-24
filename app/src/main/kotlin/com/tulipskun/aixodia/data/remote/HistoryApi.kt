package com.tulipskun.aixodia.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.tulipskun.aixodia.SettingsStore
import com.tulipskun.aixodia.data.model.ModelsPage
import com.tulipskun.aixodia.data.model.ProviderView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * REST client for history + node discovery. The base URL is either the real
 * Cloudflare Worker (production) or the local mock DB (`mock/`, testing) —
 * both speak the same contract, so the app code does not care.
 */
@JsonClass(generateAdapter = true)
data class TurnRow(
    @Json(name = "seq") val seq: Long = 0,
    @Json(name = "role") val role: String = "",
    @Json(name = "agent") val agent: String = "",
    @Json(name = "job_id") val jobId: String = "",
    @Json(name = "text") val text: String = "",
    @Json(name = "created_at") val createdAt: Long = 0,
)

@JsonClass(generateAdapter = true)
data class TurnsPage(@Json(name = "turns") val turns: List<TurnRow> = emptyList())

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
    private val client = OkHttpClient()
    private val sessionsAdapter = moshi.adapter(Array<SessionRow>::class.java)
    private val turnsAdapter = moshi.adapter(TurnsPage::class.java)
    private val modelsAdapter = moshi.adapter(ModelsPage::class.java)
    private val nodeAdapter = moshi.adapter(NodeInfo::class.java)

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

    suspend fun sessions(): List<SessionRow> = withContext(Dispatchers.IO) {
        val c = settings.current()
        val base = absoluteUrl(c.workerUrl) ?: return@withContext emptyList()
        runCatching {
            val req = Request.Builder().url("$base/api/sessions")
                .header("Authorization", "Bearer ${c.token}").get().build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use emptyList()
                sessionsAdapter.fromJson(r.body!!.source())?.toList() ?: emptyList()
            }
        }.getOrDefault(emptyList())
    }

    /** Creates the session on the DB side too, so the daemon and phone agree. */
    suspend fun createSession(id: String, title: String): Boolean = withContext(Dispatchers.IO) {
        val c = settings.current()
        val base = absoluteUrl(c.workerUrl) ?: return@withContext false
        val body = """{"id":"$id","title":"$title"}"""
        runCatching {
            val req = Request.Builder().url("$base/api/sessions")
                .header("Authorization", "Bearer ${c.token}")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** Renames a chat, the way Gemini/ChatGPT let you retitle a conversation. */
    suspend fun renameSession(sessionId: String, title: String): Boolean = withContext(Dispatchers.IO) {
        val c = settings.current()
        val base = absoluteUrl(c.workerUrl) ?: return@withContext false
        val body = """{"title":"${title.replace("\\", "\\\\").replace("\"", "\\\"")}"}"""
        runCatching {
            val req = Request.Builder().url("$base/api/sessions/$sessionId")
                .header("Authorization", "Bearer ${c.token}")
                .header("Content-Type", "application/json")
                .patch(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** Deletes a chat with its history on both sides. */
    suspend fun deleteSession(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        val c = settings.current()
        val base = absoluteUrl(c.workerUrl) ?: return@withContext false
        runCatching {
            val req = Request.Builder().url("$base/api/sessions/$sessionId")
                .header("Authorization", "Bearer ${c.token}")
                .delete()
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** Newest page for a session; the app calls this on open and on reconnect. */
    suspend fun latest(sessionId: String, limit: Int = 200): List<TurnRow> = turns(sessionId, 0, limit)

    suspend fun turns(sessionId: String, beforeSeq: Long, limit: Int = 50): List<TurnRow> =
        withContext(Dispatchers.IO) {
            val c = settings.current()
            val base = absoluteUrl(c.workerUrl) ?: return@withContext emptyList()
            val before = if (beforeSeq <= 0) Long.MAX_VALUE else beforeSeq
            val url = "$base/api/sessions/$sessionId/turns?before_seq=$before&limit=$limit"
            runCatching {
                val req = Request.Builder().url(url)
                    .header("Authorization", "Bearer ${c.token}").get().build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@use emptyList()
                    turnsAdapter.fromJson(r.body!!.source())?.turns ?: emptyList()
                }
            }.getOrDefault(emptyList())
        }

    /**
     * The providers and models the daemon can route to right now, so the app
     * never has to hardcode a model list or a provider name.
     */
    suspend fun models(): List<ProviderView> = withContext(Dispatchers.IO) {
        val c = settings.current()
        val base = absoluteUrl(c.workerUrl) ?: return@withContext emptyList()
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
        val base = absoluteUrl(c.workerUrl) ?: return@withContext false
        val body = """{"provider":"$provider","model":"$model"}"""
        runCatching {
            val req = Request.Builder().url("$base/api/sessions/$sessionId")
                .header("Authorization", "Bearer ${c.token}")
                .header("Content-Type", "application/json")
                .patch(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** Connectivity check for the Settings screen: session count or throw. */
    suspend fun ping(workerOverride: String = "", tokenOverride: String = ""): Int =
        withContext(Dispatchers.IO) {
            val c = settings.current()
            val base = absoluteUrl(workerOverride.ifBlank { c.workerUrl })
                ?: throw IllegalStateException("ยังไม่ได้ใส่ Worker URL")
            val tok = tokenOverride.ifEmpty { c.token }
            val req = Request.Builder().url("$base/api/sessions")
                .header("Authorization", "Bearer $tok").get().build()
            client.newCall(req).execute().use { r ->
                when {
                    r.code == 401 -> throw IllegalStateException("401 token ผิด")
                    r.code == 404 -> throw IllegalStateException("404 ยังไม่มี DB/Worker ตรงนี้")
                    !r.isSuccessful -> throw IllegalStateException("HTTP ${r.code}")
                    else -> sessionsAdapter.fromJson(r.body!!.source())?.size ?: 0
                }
            }
        }

    /** Quick-tunnel discovery: where is the ai daemon right now? */
    suspend fun node(workerOverride: String = "", tokenOverride: String = ""): NodeInfo? =
        withContext(Dispatchers.IO) {
            val c = settings.current()
            val base = absoluteUrl(workerOverride.ifBlank { c.workerUrl })
                ?: throw IllegalStateException("ยังไม่ได้ใส่ Worker URL")
            val tok = tokenOverride.ifEmpty { c.token }
            val req = Request.Builder().url("$base/api/node")
                .header("Authorization", "Bearer $tok").get().build()
            try {
                client.newCall(req).execute().use { r ->
                    if (r.code == 401) throw IllegalStateException("401 token ผิด")
                    if (r.code == 404) throw IllegalStateException("404 ยังไม่มี DB/Worker ตรงนี้")
                    if (!r.isSuccessful) return@withContext null
                    nodeAdapter.fromJson(r.body!!.source())
                }
            } catch (e: IllegalStateException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

    /** https://x.trycloudflare.com -> wss://x.trycloudflare.com/ws */
    fun wsUrlFor(tunnelUrl: String): String =
        tunnelUrl.replaceFirst("https://", "wss://").trimEnd('/') + "/ws"
}
