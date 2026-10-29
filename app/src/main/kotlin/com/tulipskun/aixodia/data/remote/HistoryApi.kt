package com.tulipskun.aixodia.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import org.json.JSONArray
import org.json.JSONObject
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.tulipskun.aixodia.SettingsStore
import com.tulipskun.aixodia.data.runCatchingCancellable
import com.tulipskun.aixodia.data.model.AgentSettings
import com.tulipskun.aixodia.data.model.GenerationSettings
import com.tulipskun.aixodia.data.model.SettingsView
import com.tulipskun.aixodia.data.model.ModelsPage
import com.tulipskun.aixodia.data.model.ProvidersPage
import com.tulipskun.aixodia.data.model.ProviderStatus
import com.tulipskun.aixodia.data.model.ProviderView
import com.tulipskun.aixodia.data.model.StoredTotals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.InputStream
import java.net.URLEncoder

/**
 * The app's two back ends in one place.
 *
 *  - **the daemon over its tunnel** first, for everything it serves: the
 *    provider catalogue, provider health, per-chat model pins, session
 *    writes and the agent settings, because validating and writing those
 *    needs the running router rather than the database;
 *  - **Cloudflare D1** through `D1Api` for the history reads whenever the
 *    tunnel has nothing to dial: the chat list and the pages of a thread are
 *    read straight out of `chat_sessions`/`chat_turns` — the same tables the
 *    engine serves over the tunnel — so a stopped daemon does not hide the
 *    history. Reads only: writes still travel to the engine.
 */
@JsonClass(generateAdapter = true)
data class TurnRow(
    /** The engine's sequential message id; 0 only on older engines. */
    @Json(name = "id") val messageId: Long = 0,
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
    @Json(name = "reasoning_tokens") val reasoningTokens: Int = 0,
    @Json(name = "reasoning_content") val reasoningContent: String = "",
    @Json(name = "input_includes_cache") val inputIncludesCache: Boolean = false,
    @Json(name = "duration_ms") val durationMs: Long = 0,
    @Json(name = "generation_ms") val generationMs: Long = 0,
    @Json(name = "block") val block: String = "",
)


@JsonClass(generateAdapter = true)
data class NodeInfo(
    @Json(name = "tunnel_url") val tunnelUrl: String = "",
    @Json(name = "version") val version: String = "",
    @Json(name = "online") val online: Boolean = false,
    @Json(name = "heartbeat_age_s") val ageS: Long = -1,
    /** Unix second the daemon process started; 0 when unknown. */
    @Json(name = "started") val startedAt: Long = 0,
)

@JsonClass(generateAdapter = true)
data class SessionRow(
    @Json(name = "id") val id: String = "",
    @Json(name = "title") val title: String = "",
    @Json(name = "model") val model: String = "",
    @Json(name = "provider") val provider: String = "",
    @Json(name = "updated_at") val updatedAt: Long = 0,
    // The engine sums the whole chat's cost over its own turns and sends it
    // with the session, so the phone draws the strip from metadata instead of
    // adding up whichever rows it happens to have loaded. Null means the engine
    // predates this field; the last cached metadata, if any, stays in place.
    @Json(name = "usage") val usage: SessionUsageRow? = null,
)

/** A chat's goal and plan lists as the engine's objectives endpoint serves them. */
data class ObjectiveRow(
    val id: String = "",
    val content: String = "",
    /** open, done, or dropped. */
    val status: String = "open",
)

data class ObjectivesPage(
    val goals: List<ObjectiveRow> = emptyList(),
    val plans: List<ObjectiveRow> = emptyList(),
)

/** A chat's cost as the engine summed it. Mirrors session.SessionUsage. */
data class SessionUsageRow(
    @Json(name = "prompt_tokens") val promptTokens: Int = 0,
    @Json(name = "cache_read") val cacheRead: Int = 0,
    @Json(name = "fresh_input") val freshInput: Int = 0,
    @Json(name = "output_tokens") val outTokens: Int = 0,
    @Json(name = "total_ms") val totalMs: Long = 0,
    @Json(name = "last_turn_ms") val lastTurnMs: Long = 0,
)

/** Reads the optional usage object without inventing zeroes for an old engine. */
private fun sessionUsageRow(o: JSONObject?): SessionUsageRow? {
    if (o == null) return null
    return SessionUsageRow(
        promptTokens = o.optInt("prompt_tokens"),
        cacheRead = o.optInt("cache_read"),
        freshInput = o.optInt("fresh_input"),
        outTokens = o.optInt("output_tokens"),
        totalMs = o.optLong("total_ms"),
        lastTurnMs = o.optLong("last_turn_ms"),
    )
}

/**
 * D1 answers every cell through JSON: numbers arrive as numbers, but a number
 * the database went through a text column reads back as a string. These three
 * readers accept both, and read SQL NULL as the column's default instead of
 * optString's literal "null".
 */
internal fun d1Text(o: JSONObject, key: String): String {
    val v = o.opt(key)
    if (v == null || v === JSONObject.NULL) return ""
    return v.toString()
}

internal fun d1Long(o: JSONObject, key: String): Long = when (val v = o.opt(key)) {
    null, JSONObject.NULL -> 0L
    is Number -> v.toLong()
    else -> v.toString().toLongOrNull() ?: 0L
}

internal fun d1Int(o: JSONObject, key: String): Int = d1Long(o, key).toInt()

/** A D1 `chat_sessions` row — usage columns included — as the merge uses it. */
internal fun d1SessionRow(o: JSONObject): SessionRow = SessionRow(
    id = d1Text(o, "id"),
    title = d1Text(o, "title"),
    model = d1Text(o, "model"),
    provider = d1Text(o, "provider"),
    updatedAt = d1Long(o, "updated_at"),
    usage = SessionUsageRow(
        promptTokens = d1Int(o, "usage_prompt_tokens"),
        cacheRead = d1Int(o, "usage_cache_read"),
        freshInput = d1Int(o, "usage_fresh_input"),
        outTokens = d1Int(o, "usage_output_tokens"),
        totalMs = d1Long(o, "usage_total_ms"),
        lastTurnMs = d1Long(o, "usage_last_turn_ms"),
    ),
)

/** A D1 `chat_turns` row as the thread maps it. */
internal fun d1TurnRow(o: JSONObject): TurnRow = TurnRow(
    messageId = d1Long(o, "id"),
    seq = d1Long(o, "seq"),
    role = d1Text(o, "role"),
    agent = d1Text(o, "agent"),
    jobId = d1Text(o, "job_id"),
    text = d1Text(o, "text"),
    createdAt = d1Long(o, "created_at"),
    model = d1Text(o, "model"),
    inputTokens = d1Int(o, "input_tokens"),
    outputTokens = d1Int(o, "output_tokens"),
    cacheRead = d1Int(o, "cache_read_tokens"),
    cacheWrite = d1Int(o, "cache_write_tokens"),
    reasoningTokens = d1Int(o, "reasoning_tokens"),
    reasoningContent = d1Text(o, "reasoning_content"),
    inputIncludesCache = d1Int(o, "input_includes_cache") != 0,
    durationMs = d1Long(o, "duration_ms"),
    generationMs = d1Long(o, "generation_ms"),
    block = d1Text(o, "block"),
)

/** The engine's session metadata in the shape the room cache and strip use. */
fun SessionUsageRow.toStoredTotals(): StoredTotals = StoredTotals(
    promptTokens = promptTokens,
    cacheRead = cacheRead,
    freshInput = freshInput,
    outTokens = outTokens,
    lastTurnMs = lastTurnMs,
    totalMs = totalMs,
)

/**
 * The result of reading the engine's live shells, keeping the three outcomes the
 * terminal must not confuse: what it holds, an engine with no list to give, and
 * an engine that was not there to ask.
 */
sealed interface TerminalShells {
    /** The engine answered. This is every shell it holds, possibly none. */
    data class Listed(val rows: List<JSONObject>) : TerminalShells

    /** The engine has no `/v1/terminals`: a build too old to list its shells. */
    data object Unlisted : TerminalShells

    /** Nothing was answered: the daemon, its tunnel, or the network is down. */
    data object Unreachable : TerminalShells
}

/**
 * Picks one page out of an oldest-first transcript.
 *
 * beforeSeq <= 0 means "the newest page", which is what opening a long chat
 * needs: the first page used to be the oldest rows, so a phone that had no
 * cache showed the start of the thread and numbered the next send from there,
 * colliding with turns it had never downloaded.
 */
internal fun pageTurns(rows: List<TurnRow>, beforeSeq: Long, limit: Int): List<TurnRow> {
    val before = if (beforeSeq <= 0L) rows else rows.filter { it.seq < beforeSeq }
    // Zero means "no page bound", which the incremental tail read uses: it
    // needs everything after a seq, and limiting that to the newest page would
    // silently skip turns when more than one page landed while offline.
    return if (limit <= 0) before else before.takeLast(limit)
}

class HistoryApi(
    private val settings: SettingsStore,
    private val d1: D1Api = D1Api(settings),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build(),
) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    // History is Cloudflare D1 read directly; everything in this class that
    // talks to the daemon calls the tunnel URL over ordinary HTTP.
    // The settings calls are not all instant: asking the daemon to re-check its
    // providers means waiting for the gateways to answer, through a connection
    // that can take seconds per round trip. The default 10s read timeout cut
    // those calls off halfway and left the screen with nothing to show.
    private val modelsAdapter = moshi.adapter(ModelsPage::class.java)
    private val providersAdapter = moshi.adapter(ProvidersPage::class.java)
    private val providerListAdapter = moshi.adapter<List<ProviderStatus>>(Types.newParameterizedType(List::class.java, ProviderStatus::class.java))
    private val providerAdapter = moshi.adapter(ProviderStatus::class.java)
    private val settingsAdapter = moshi.adapter(SettingsView::class.java)

    /**
     * Builds an absolute URL, or null when the app has no endpoint configured
     * yet. A fresh install has empty settings by design, and that must look
     * like "not configured", never like a crash: okhttp throws on a relative
     * URL, and that exception used to escape into the ViewModel scope.
     */
    /** Asks the running daemon to end this session and start the next one. */
    suspend fun triggerPushNow(): Boolean = withContext(Dispatchers.IO) {
        val c = settings.current()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext false
        runCatching {
            val req = Request.Builder().url("$base/restart")
                .header("Authorization", "Bearer ${c.token}")
                .header("Content-Type", "application/json")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** Ends this session: the engine writes ~/.exit and the notebook restarts itself. */
    suspend fun pushNow(): Boolean = triggerPushNow()

    /** Recent engine log entries (newest first), for debugging. */
    suspend fun fetchLogs(limit: Int = 200): List<JSONObject> = withContext(Dispatchers.IO) {
        val c = settings.current()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext emptyList()
        runCatching {
            val req = Request.Builder().url("$base/api/logs?limit=$limit")
                .header("Authorization", "Bearer ${c.token}")
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                val env = runCatching { JSONObject(text) }.getOrNull() ?: return@use emptyList()
                val arr = env.optJSONArray("logs") ?: return@use emptyList()
                val out = ArrayList<JSONObject>()
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
                out
            }
        }.getOrDefault(emptyList())
    }

    private fun absoluteUrl(raw: String): String? {
        val base = raw.trim().trimEnd('/')
        if (base.isEmpty()) return null
        if (!base.startsWith("http://") && !base.startsWith("https://")) return null
        return base
    }

    /**
     * Every chat the engine knows, newest first. The list is read over the
     * tunnel first and straight out of D1 when the tunnel has nothing to
     * dial, so a stopped daemon decays into a history viewer instead of an
     * empty drawer.
     */
    suspend fun sessions(): List<SessionRow> = sessionsOrNull().orEmpty()

    /**
     * Null when neither the tunnel nor D1 could be read; an empty list only
     * when the store that answered truly has no chats. The daemon read and
     * the D1 read are separate functions instead of one swallowed catch
     * because the difference feeds syncSessions: a half-read must prune
     * nothing, a truthful empty list is the authority to prune with.
     */
    suspend fun sessionsOrNull(): List<SessionRow>? = withContext(Dispatchers.IO) {
        daemonSessions() ?: d1Sessions()
    }

    /** The tunnel read of the chat list. Null when it cannot be dialled. */
    private suspend fun daemonSessions(): List<SessionRow>? = withContext(Dispatchers.IO) {
        val c = settings.current()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext null
        runCatchingCancellable {
            val req = Request.Builder().url("$base/v1/sessions")
                .header("Authorization", "Bearer ${c.token}")
                .header("Accept", "application/json")
                .get().build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val text = r.body?.string().orEmpty()
                val env = runCatching { JSONObject(text) }.getOrNull() ?: return@use null
                // A reply without the field is not "the engine has no chats".
                // Treat it as unreachable so syncSessions never prunes the
                // phone's history over a half-read response.
                val arr = env.optJSONArray("sessions") ?: return@use null
                val out = ArrayList<SessionRow>()
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { o ->
                    out.add(SessionRow(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        provider = o.optString("provider"),
                        model = o.optString("model"),
                        updatedAt = o.optLong("updated_at"),
                        usage = sessionUsageRow(o.optJSONObject("usage")),
                    ))
                }
                out
            }
        }.getOrNull()
    }

    /** The direct D1 read of the chat list. Null when D1 cannot be read. */
    private suspend fun d1Sessions(): List<SessionRow>? = withContext(Dispatchers.IO) {
        runCatchingCancellable { d1.chatSessions().map(::d1SessionRow) }.getOrNull()
    }

    /**
     * Creates the chat on the daemon and returns its id, or "" on failure.
     * [id] is a locally-created draft id when the phone is promoting a New
     * chat on its first send; the daemon keeps that id so no local rows have
     * to move. Blank asks the daemon to generate one.
     */
    suspend fun createSession(id: String, title: String): String = withContext(Dispatchers.IO) {
        val c = settings.current()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext ""
        runCatchingCancellable {
            val body = JSONObject().put("title", title)
            if (id.isNotBlank()) body.put("id", id)
            val request = body.toString()
            val req = Request.Builder().url("$base/v1/sessions")
                .header("Authorization", "Bearer ${c.token}")
                .header("Content-Type", "application/json")
                .post(request.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                val env = runCatching { JSONObject(text) }.getOrNull() ?: return@use ""
                env.optString("id").ifBlank { "" }
            }
        }.getOrDefault("")
    }

    /** Renames a chat on the daemon. */
    suspend fun renameSession(sessionId: String, title: String): Boolean = withContext(Dispatchers.IO) {
        val c = settings.current()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext false
        runCatching {
            val body = JSONObject().put("title", title).toString()
            val req = Request.Builder().url("$base/v1/sessions/$sessionId")
                .header("Authorization", "Bearer ${c.token}")
                .header("Content-Type", "application/json")
                .patch(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { r -> r.isSuccessful }
        }.getOrDefault(false)
    }

    /** Deletes a chat with its history, on the daemon. */
    suspend fun deleteSession(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        val c = settings.current()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext false
        runCatching {
            val req = Request.Builder().url("$base/v1/sessions/$sessionId")
                .header("Authorization", "Bearer ${c.token}")
                .delete()
                .build()
            client.newCall(req).execute().use { r -> r.isSuccessful }
        }.getOrDefault(false)
    }

    /** Newest page for a session; the app calls this on open and on reconnect. */
    suspend fun latest(sessionId: String, limit: Int = 200): List<TurnRow> = turns(sessionId, 0, limit)
    suspend fun turnsAfter(sessionId: String, afterSeq: Long, limit: Int = 200): List<TurnRow> {
        // The catch-up read is unbounded on purpose: a reconnect can be days
        // behind, and a page-sized tail would look synced while turns in the
        // middle were still missing. limit = 0 omits the bound.
        val rows = turns(sessionId, 0, 0)
        return if (afterSeq <= 0L) rows else rows.filter { it.seq > afterSeq }
    }

    /**
     * One page of history, oldest-first — the daemon over the tunnel, or
     * straight out of D1's `chat_turns` when the tunnel cannot be dialled (a
     * moved trycloudflare URL, an engine that is down, a fresh install before
     * the first announcement). Both paths yield the same TurnRow shape, so
     * the merge logic never learns which one answered.
     */
    suspend fun turns(sessionId: String, beforeSeq: Long, limit: Int = 50): List<TurnRow> =
        withContext(Dispatchers.IO) {
            // A failed fallback is not an authoritative empty page. Let paging
            // retain its older-history marker and offer another attempt.
            val rows = daemonTurns(sessionId, beforeSeq, limit)
                ?: d1.chatTurns(sessionId, beforeSeq, limit).map(::d1TurnRow)
            pageTurns(rows, beforeSeq, limit)
        }

    /**
     * The tunnel read of one page. Null when the daemon is not configured or
     * the request failed for any reason — that is exactly the case that then
     * reads D1 instead. An older engine that ignores the page parameters
     * still sends everything; pageTurns cuts that down, so the phone keeps
     * working against it unchanged.
     */
    private suspend fun daemonTurns(sessionId: String, beforeSeq: Long, limit: Int): List<TurnRow>? =
        withContext(Dispatchers.IO) {
            val c = settings.current()
            val base = absoluteUrl(c.daemonUrl) ?: return@withContext null
            val query = buildString {
                if (limit > 0) append("limit=").append(limit)
                if (beforeSeq > 0L) {
                    if (isNotEmpty()) append('&')
                    append("before_seq=").append(beforeSeq)
                }
            }
            val url = "$base/v1/sessions/$sessionId/turns" + if (query.isEmpty()) "" else "?$query"
            runCatchingCancellable {
                val req = Request.Builder().url(url)
                    .header("Authorization", "Bearer ${c.token}")
                    .header("Accept", "application/json")
                    .get().build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val text = r.body?.string().orEmpty()
                    val env = runCatching { JSONObject(text) }.getOrNull() ?: return@use null
                    val arr = env.optJSONArray("turns") ?: return@use null
                    val out = ArrayList<TurnRow>()
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { o ->
                        out.add(TurnRow(
                            messageId = o.optLong("id"),
                            seq = o.optLong("seq"),
                            role = o.optString("role"),
                            agent = o.optString("agent"),
                            jobId = o.optString("job_id"),
                            text = o.optString("text"),
                            createdAt = o.optLong("created_at"),
                            model = o.optString("model"),
                            inputTokens = o.optInt("input_tokens"),
                            outputTokens = o.optInt("output_tokens"),
                            cacheRead = o.optInt("cache_read_tokens"),
                            cacheWrite = o.optInt("cache_write_tokens"),
                            reasoningTokens = o.optInt("reasoning_tokens"),
                            reasoningContent = o.optString("reasoning_content"),
                            inputIncludesCache = o.optInt("input_includes_cache") != 0,
                            durationMs = o.optLong("duration_ms"),
                            generationMs = o.optLong("generation_ms"),
                            block = o.optString("block"),
                        ))
                    }
                    // oldest-first ordering
                    out.sortedBy { it.seq }
                }
            }.getOrNull()
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
        runCatchingCancellable {
            val req = Request.Builder().url("$base/api/sessions/$sessionId")
                .header("Authorization", "Bearer ${c.token}")
                .header("Content-Type", "application/json")
                .patch(body.toString().toRequestBody("application/json".toMediaType()))
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
        runCatchingCancellable {
            val req = Request.Builder().url("$base/api/sessions/$sessionId")
                .header("Authorization", "Bearer ${c.token}")
                .header("Content-Type", "application/json")
                .patch(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /**
     * Saves one chat's own knobs. A null field is left alone rather than cleared,
     * so a sheet that only changed the temperature does not silently drop the rest.
     */
    suspend fun setSessionGeneration(
        sessionId: String,
        generation: GenerationSettings,
        clear: Boolean = false,
        clearKnobs: List<String> = emptyList(),
    ): Boolean = withContext(Dispatchers.IO) {
        val c = settings.current()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext false
        val g = JSONObject()
        if (clear) {
            val body = JSONObject().put("clear_generation", true).toString()
            return@withContext patchSession(base, c.token, sessionId, body)
        }
        if (clearKnobs.isNotEmpty()) {
            val body = JSONObject().put("clear_knobs", JSONArray(clearKnobs)).toString()
            if (!patchSession(base, c.token, sessionId, body)) return@withContext false
        }
        generation.thinkingLevel.takeIf { it.isNotBlank() }?.let { g.put("thinking_level", it) }
        generation.temperature?.let { g.put("temperature", it) }
        generation.topP?.let { g.put("top_p", it) }
        generation.topK?.let { g.put("top_k", it) }
        if (!generation.stopSequences.isNullOrEmpty()) {
            g.put("stop_sequences", JSONArray(generation.stopSequences))
        }
        generation.presencePenalty?.let { g.put("presence_penalty", it) }
        generation.frequencyPenalty?.let { g.put("frequency_penalty", it) }
        generation.seed?.let { g.put("seed", it) }
        if (generation.maxOutputTokens != 0) g.put("max_output_tokens", generation.maxOutputTokens)
        val body = JSONObject().put("generation", g).toString()
        patchSession(base, c.token, sessionId, body)
    }

    private fun patchSession(base: String, token: String, sessionId: String, body: String): Boolean {
        return runCatching {
            val req = Request.Builder().url("$base/api/sessions/$sessionId")
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .patch(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** Every configured provider with its reachability and last error. */
    suspend fun providers(): List<ProviderStatus> = withContext(Dispatchers.IO) {
        val c = settings.current()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext cachedProviders()
        val list = runCatching {
            val req = Request.Builder().url("$base/api/providers")
                .header("Authorization", "Bearer ${c.token}").get().build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use emptyList()
                providersAdapter.fromJson(r.body!!.source())?.providers ?: emptyList()
            }
        }.getOrDefault(emptyList())
        if (list.isNotEmpty()) runCatching {
            settings.saveProvidersCache(base, providerListAdapter.toJson(list))
        }
        if (list.isNotEmpty()) list else cachedProviders()
    }

    /** Last providers list cached locally, so the screen is not empty offline. */
    suspend fun cachedProviders(): List<ProviderStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val current = settings.current().daemonUrl.trim().trimEnd('/')
            val (raw, cachedFor) = settings.providersCacheSnapshot()
            // A cache from another tunnel describes another engine. Showing it
            // here would claim providers and reachability the current daemon
            // never reported.
            if (raw.isBlank() || cachedFor.isBlank() || cachedFor != current) emptyList()
            else providerListAdapter.fromJson(raw) ?: emptyList()
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
        id: String, adapter: String, endpoint: String, keys: List<String>,
    ): String = withContext(Dispatchers.IO) {
        val c = settings.current()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext "Daemon URL not configured"
        val body = JSONObject()
            .put("id", id)
            .put("adapter", adapter)
            .put("endpoint", endpoint)
            .put("keys", JSONArray(keys))
            .toString()
        runCatching {
            val req = Request.Builder().url("$base/api/providers")
                .header("Authorization", "Bearer ${c.token}")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { r ->
                failure("Added $id", r).let { if (r.isSuccessful) it else "Add failed: $it" }
            }
        }.getOrDefault("Add failed")
    }

    /** Edits one provider's keys: add, remove by position, or replace wholesale. */
    suspend fun changeKeys(
        providerId: String, add: List<String> = emptyList(), remove: List<Int> = emptyList(), replace: List<String> = emptyList(),
    ): String = withContext(Dispatchers.IO) {
        val c = settings.current()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext "Daemon URL not configured"
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
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { r ->
                failure("Updated keys for $providerId", r).let { if (r.isSuccessful) it else "Update failed: $it" }
            }
        }.getOrDefault("Update failed")
    }

    suspend fun removeProvider(providerId: String): String = withContext(Dispatchers.IO) {
        val c = settings.current()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext "Daemon URL not configured"
        runCatching {
            val req = Request.Builder().url("$base/api/providers/$providerId")
                .header("Authorization", "Bearer ${c.token}").delete().build()
            client.newCall(req).execute().use { r ->
                failure("Removed $providerId", r).let { if (r.isSuccessful) it else "Remove failed: $it" }
            }
        }.getOrDefault("Remove failed")
    }

    /**
     * Saves where each agent runs and how it is asked to answer. The knobs are
     * only written when the caller set one: an unset knob is left to the
     * provider's own default, and sending a zero would be an instruction.
     */
    suspend fun saveAgentSettings(
        settingsBody: SettingsView,
        clearMain: List<String> = emptyList(),
    ): String = withContext(Dispatchers.IO) {
        val c = settings.current()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext "Daemon URL not configured"
        val body = JSONObject()
            .put("main", agentBody(settingsBody.main, clearMain))
            .toString()
        runCatching {
            val req = Request.Builder().url("$base/api/settings")
                .header("Authorization", "Bearer ${c.token}")
                .header("Content-Type", "application/json")
                .put(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { r ->
                failure("Agent settings saved", r).let { if (r.isSuccessful) it else "Save failed: $it" }
            }
        }.getOrDefault("Save failed")
    }

    /**
     * One agent's route plus its knobs. A knob the caller left unset is not sent,
     * and a knob the reader cleared is named in clear_knobs: the daemon has to be
     * able to tell "I did not touch this" from "take this away".
     */
    private fun agentBody(agent: AgentSettings, clearKnobs: List<String> = emptyList()): JSONObject {
        val body = JSONObject()
            .put("provider", agent.provider)
            .put("model", agent.model)
        val g = agent.generation
        val generation = JSONObject()
        if (g.thinkingLevel.isNotBlank()) generation.put("thinking_level", g.thinkingLevel)
        g.temperature?.let { generation.put("temperature", it) }
        g.topP?.let { generation.put("top_p", it) }
        g.topK?.let { generation.put("top_k", it) }
        if (!g.stopSequences.isNullOrEmpty()) {
            generation.put("stop_sequences", JSONArray(g.stopSequences))
        }
        g.presencePenalty?.let { generation.put("presence_penalty", it) }
        g.frequencyPenalty?.let { generation.put("frequency_penalty", it) }
        g.seed?.let { generation.put("seed", it) }
        if (g.maxOutputTokens != 0) generation.put("max_output_tokens", g.maxOutputTokens)
        body.put("generation", generation)
        if (clearKnobs.isNotEmpty()) body.put("clear_knobs", JSONArray(clearKnobs))
        return body
    }

    suspend fun agentSettings(): SettingsView? = withContext(Dispatchers.IO) {
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

    /** A single-use ticket for the dedicated terminal WebSocket. It keeps the
     * long-lived API token out of a URL that a proxy or log may keep. */
    suspend fun terminalTicket(): String? = withContext(Dispatchers.IO) {
        val c = settings.current()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext null
        runCatching {
            val req = Request.Builder().url("$base/v1/terminals/ticket")
                .header("Authorization", "Bearer ${c.token}")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                runCatching { JSONObject(text).optString("ticket") }.getOrNull().orEmpty().ifBlank { null }
            }
        }.getOrNull()
    }

    /** Ends a shell the phone closed, even when another tab is attached. */
    suspend fun closeTerminal(id: String): Boolean = withContext(Dispatchers.IO) {
        if (id.isBlank()) return@withContext false
        val c = settings.current()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext false
        runCatching {
            val req = Request.Builder().url("$base/v1/terminals/${URLEncoder.encode(id, "UTF-8")}")
                .header("Authorization", "Bearer ${c.token}")
                .delete().build()
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /**
     * Sessions the engine still holds, including tmux shells that survived a
     * restart — and the reason the list is not available when it is not.
     *
     * The terminal has to tell [TerminalShells.Unreachable] apart from an empty
     * list. An engine that is stopped or restarting answers nothing, and opening
     * the saved tabs against that silence would start brand-new shells under the
     * ids of shells that are gone.
     */
    suspend fun terminalShells(): TerminalShells = withContext(Dispatchers.IO) {
        val c = settings.current()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext TerminalShells.Unreachable
        runCatching {
            val req = Request.Builder().url("$base/v1/terminals")
                .header("Authorization", "Bearer ${c.token}")
                .header("Accept", "application/json")
                .get().build()
            client.newCall(req).execute().use { r ->
                // 404/405 is the engine answering that it has no such route (an
                // older build), which is information; 502/503 from the tunnel is
                // the engine not being there.
                if (r.code == 404 || r.code == 405) return@use TerminalShells.Unlisted
                if (!r.isSuccessful) return@use TerminalShells.Unreachable
                val array = runCatching {
                    JSONObject(r.body?.string().orEmpty()).optJSONArray("terminals")
                }.getOrNull() ?: return@use TerminalShells.Unlisted
                val rows = ArrayList<JSONObject>(array.length())
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let(rows::add)
                }
                TerminalShells.Listed(rows)
            }
        }.getOrDefault(TerminalShells.Unreachable)
    }

    /**
     * One chat's goals and plans with their statuses, for the goals card.
     * Null when the engine cannot be reached or predates the endpoint.
     */
    suspend fun objectives(sessionId: String): ObjectivesPage? = withContext(Dispatchers.IO) {
        val c = settings.current()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext null
        runCatchingCancellable {
            val req = Request.Builder().url("$base/v1/sessions/$sessionId/objectives")
                .header("Authorization", "Bearer ${c.token}")
                .header("Accept", "application/json")
                .get().build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val env = runCatching { JSONObject(r.body!!.string()) }.getOrNull() ?: return@use null
                ObjectivesPage(
                    goals = objectiveRows(env.optJSONArray("goals")),
                    plans = objectiveRows(env.optJSONArray("plans")),
                )
            }
        }.getOrDefault(null)
    }

    private fun objectiveRows(arr: JSONArray?): List<ObjectiveRow> {
        if (arr == null) return emptyList()
        val out = ArrayList<ObjectiveRow>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                ObjectiveRow(
                    id = o.optString("id"),
                    content = o.optString("content"),
                    status = o.optString("status").ifBlank { "open" },
                )
            )
        }
        return out
    }

    /** Connectivity check for the Settings screen: session count, or throw. */
    suspend fun ping(): Int = withContext(Dispatchers.IO) {
        val c = settings.current()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext 0
        runCatching {
            val req = Request.Builder().url("$base/v1/sessions")
                .header("Authorization", "Bearer ${c.token}")
                .header("Accept", "application/json")
                .get().build()
            client.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                val env = runCatching { JSONObject(text) }.getOrNull() ?: return@use 0
                env.optJSONArray("sessions")?.length() ?: 0
            }
        }.getOrDefault(0)
    }

    // Uploads can be large and slow; the shared client's 150s call timeout would
    // cut a big video off halfway. This one waits, and streams the body.
    private val uploadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.MINUTES)
        .build()

    /**
     * Uploads one attachment and returns where the daemon stored it. The bytes
     * are streamed, never held in memory whole, so a large file does not have to
     * fit in RAM. Null means the upload failed.
     */
    suspend fun uploadAttachment(
        sessionId: String,
        name: String,
        mime: String,
        open: () -> InputStream?,
    ): com.tulipskun.aixodia.data.model.AttachmentRef? = withContext(Dispatchers.IO) {
        val c = settings.current()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext null
        runCatching {
            val query = "session_id=" + URLEncoder.encode(sessionId, "UTF-8") +
                "&name=" + URLEncoder.encode(name, "UTF-8")
            // The tunnel is an ordinary HTTPS address, so the file streams in
            // the request body instead of being buffered into rpc frames.
            val body = object : RequestBody() {
                override fun contentType(): MediaType? = mime.toMediaTypeOrNull()
                override fun writeTo(sink: BufferedSink) {
                    val input = open() ?: throw java.io.IOException("Cannot open file")
                    input.source().use { sink.writeAll(it) }
                }
            }
            val req = Request.Builder().url("$base/v1/attachments?$query")
                .header("Authorization", "Bearer ${c.token}")
                .post(body)
                .build()
            uploadClient.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val o = JSONObject(r.body!!.string())
                com.tulipskun.aixodia.data.model.AttachmentRef(
                    name = o.optString("name"),
                    path = o.optString("path"),
                    mime = o.optString("mime"),
                    size = o.optLong("size"),
                )
            }
        }.getOrNull()
    }

    /**
     * Resolves the Cloudflare account and database from the token and reports
     * what was found, so the settings screen can show it and remember it
     * Throws with Cloudflare's own message when it cannot.
     */
    suspend fun discover(): D1Api.Target = withContext(Dispatchers.IO) { d1.discover() }

    /**
     * Tunnel discovery: where the ai daemon is, read from the D1 `tunnel` row
     * A failure is reported rather than hidden, so the settings screen
     * can tell "D1 unreachable" from "the daemon has not beaten yet".
     */
    suspend fun node(): NodeInfo? = withContext(Dispatchers.IO) { d1.node() }


}
