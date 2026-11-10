package com.tulipskun.aixodia.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import org.json.JSONArray
import org.json.JSONObject
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.tulipskun.aixodia.ConnConfig
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
 *  - **Room** for offline history already synchronized to this device. All
 *    remote reads and writes go through the Engine's authenticated API; the
 *    Android client never accesses Cloudflare D1 or Workers KV directly.
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
    /** Unix second the daemon process started; 0 when unknown. */
    @Json(name = "started") val startedAt: Long = 0,
    /**
     * Unix second the *machine* started, which is the uptime the corner counter
     * shows: an engine update restarts the process, and the box has been up the
     * whole time. 0 on an engine too old to publish it.
     */
    @Json(name = "boot") val bootAt: Long = 0,
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
 * How the last run to finish ended, read out of the engine's `finished` slot.
 *
 * The engine keeps exactly one such answer and forgets it when it restarts, so
 * this is shown as soon as it is read and never assumed to still be there later.
 */
data class BackupRun(
    val operation: String = "",
    val ok: Boolean = false,
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
    /** What the engine said when [ok] is false; blank on a run that worked. */
    val error: String = "",
    val report: BackupRunReport? = null,
)

/**
 * The counts one completed run reported. Every field is read if present and
 * left at zero if not, so a phone newer than its engine still shows what the
 * engine did say rather than nothing at all.
 */
data class BackupRunReport(
    val name: String = "",
    val root: String = "",
    val bucket: String = "",
    val files: Int = 0,
    val dirs: Int = 0,
    val symlinks: Int = 0,
    val bytes: Long = 0,
    /** Bytes this run actually put on the wire; the rest were already there. */
    val uploadedBytes: Long = 0,
    val filesReused: Int = 0,
    val objectsReused: Int = 0,
    /** Restore only: files already on disk with the right contents. */
    val unchanged: Int = 0,
    /** The manifest this run replaced; the bucket holds only one. */
    val deletedManifests: Int = 0,
    /** Paths the run could not read, named so a silent omission stays visible. */
    val skipped: List<String> = emptyList(),
    /** Restore only: what the run could not write, named with its reason. */
    val failed: List<String> = emptyList(),
    val durationMs: Long = 0,
)

/**
 * One read of `/api/backup`: what the engine is configured to back up, what it
 * is doing right now, and how the last run ended.
 *
 * The hub token is not in here and never will be — the engine reports only its
 * last four characters, which is enough to tell two keys apart.
 */
data class BackupStatus(
    val configured: Boolean = false,
    /** Field names the engine still needs before a run can start. */
    val needs: List<String> = emptyList(),
    /**
     * Whether a restore could start. It differs from [configured] on a machine
     * whose tree is gone: a backup has to read the root, a restore is the answer
     * to the root not being there, so it creates it.
     */
    val restorable: Boolean = false,
    /** Field names a restore still needs — usually token and bucket only. */
    val needsRestore: List<String> = emptyList(),
    val bucket: String = "",
    val prefix: String = "",
    val root: String = "",
    val tokenTail: String = "",
    /** "backup" or "restore" while a job runs, "" when the engine is idle. */
    val operation: String = "",
    /** When the run in flight began, in engine unix seconds. 0 when idle. */
    val startedAt: Long = 0,
    val phase: String = "",
    val message: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    /** One line on the last run to finish, "" until this engine has done one. */
    val lastRun: String = "",
    /** The same answer as [lastRun], with every count the engine reported. */
    val run: BackupRun? = null,
    /**
     * The glob patterns this engine leaves out of a backup, or null when it does
     * not say. A backup scope is not something to guess at: an engine older than
     * the field is reported as unknown rather than as an empty list, which would
     * read as "nothing is excluded" on a machine that excludes a dozen things.
     */
    val excludes: List<String>? = null,
    /** Auto-backup configuration and live state, null when engine predates it. */
    val auto: AutoBackupStatus? = null,
    /**
     * The folders this backup is narrowed to, or null when the engine does not
     * say. An empty list is a real answer — the whole tree — while a missing
     * field means an engine older than the allowlist, whose scope the phone
     * cannot show and must not pretend to edit. It is also the capability gate
     * for the picker: an engine that reports no allowlist has no browse or
     * preview route either.
     */
    val includes: List<String>? = null,
    /** Last successful backup, persisted by the engine across restarts. */
    val lastBackup: BackupRun? = null,
) {
    val running: Boolean get() = operation.isNotBlank()

    /** Whether this engine will answer what it excludes. */
    val reportsExcludes: Boolean get() = excludes != null

    /** Whether this engine reports auto-backup status. */
    val reportsAuto: Boolean get() = auto != null

    /** Whether this engine can be asked to browse and preview a scope. */
    val reportsScope: Boolean get() = includes != null
}

/** One directory as the engine's picker sees it. */
data class BrowseEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
)

/** The answer to "what is in this folder on the engine". */
data class BackupBrowse(
    val path: String,
    /** Empty at the top of the tree — there is nowhere above `/` to show. */
    val parent: String = "",
    val entries: List<BrowseEntry> = emptyList(),
    val truncated: Boolean = false,
)

/** One top-level folder's share of a scope. */
data class FolderBytes(
    val name: String,
    val files: Int,
    val bytes: Long,
)

/** What a scope would cover, counted before any of it is uploaded. */
data class BackupPreview(
    val root: String = "",
    val files: Int = 0,
    val dirs: Int = 0,
    val symlinks: Int = 0,
    val bytes: Long = 0,
    val skipped: Int = 0,
    val skippedExamples: List<String> = emptyList(),
    val folders: List<FolderBytes> = emptyList(),
)

/**
 * A scope read that either came back with an answer or with the engine's own
 * reason for not having one. Both are shown: "this folder is engine plumbing" is
 * the reply a tapped row deserves, and a silent empty sheet reads as an empty
 * folder.
 */
data class ScopeRead<out T>(val value: T? = null, val error: String = "") {
    val ok: Boolean get() = value != null
}

/** Reads one `/api/backup/browse` answer. Unreadable rows are left out. */
internal fun parseBackupBrowse(o: JSONObject): BackupBrowse {
    val rows = o.optJSONArray("entries")
    val entries = ArrayList<BrowseEntry>(rows?.length() ?: 0)
    if (rows != null) {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            entries.add(
                BrowseEntry(
                    name = row.optString("name"),
                    path = row.optString("path"),
                    isDir = row.optBoolean("is_dir"),
                ),
            )
        }
    }
    return BackupBrowse(
        path = o.optString("path"),
        parent = if (o.isNull("parent")) "" else o.optString("parent"),
        entries = entries,
        truncated = o.optBoolean("truncated"),
    )
}

/** Reads one `/api/backup/preview` answer, heaviest folder first. */
internal fun parseBackupPreview(o: JSONObject): BackupPreview {
    val rows = o.optJSONArray("folders")
    val folders = ArrayList<FolderBytes>(rows?.length() ?: 0)
    if (rows != null) {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            folders.add(
                FolderBytes(
                    name = row.optString("name"),
                    files = row.optInt("files"),
                    bytes = row.optLong("bytes"),
                ),
            )
        }
    }
    return BackupPreview(
        root = o.optString("root"),
        files = o.optInt("files"),
        dirs = o.optInt("dirs"),
        symlinks = o.optInt("symlinks"),
        bytes = o.optLong("bytes"),
        skipped = o.optInt("skipped"),
        skippedExamples = stringList(o.optJSONArray("skipped_examples")),
        folders = folders,
    )
}

/**
 * The automatic backup: when the engine runs one on its own, and what it can
 * see right now. The thresholds are the operator's settings; the streak, the CPU
 * reading and the last run are the engine's live answer, which is why they come
 * in one object rather than as a second request.
 */
data class AutoBackupStatus(
    val enabled: Boolean = false,
    val idleStreakSecs: Long = 0,
    val cpuCurrent: Double = 0.0,
    val lastAutoBackupAt: Long = 0,
    val idleSecs: Long = 300,
    val cpuThreshold: Double = 10.0,
    val cooldownSecs: Long = 1800,
    val restoreReady: Boolean = false,
    val cpuHistory: List<Double> = emptyList(),
)

/** Reads one `/api/backup` answer. Only the fields that are there are read. */
internal fun parseBackupStatus(o: JSONObject): BackupStatus {
    val active = o.optJSONObject("active")
    val progress = active?.optJSONObject("progress")
    // An engine older than the split answers only "configured", and on those
    // builds a restore really did require the root to exist.
    val restorable =
        if (o.has("restorable")) o.optBoolean("restorable") else o.optBoolean("configured")
    return BackupStatus(
        configured = o.optBoolean("configured"),
        needs = stringList(o.optJSONArray("needs")),
        restorable = restorable,
        needsRestore = stringList(o.optJSONArray("needs_restore")),
        bucket = o.optString("bucket"),
        prefix = o.optString("prefix"),
        root = o.optString("root"),
        tokenTail = o.optJSONObject("token")?.optString("tail").orEmpty(),
        operation = active?.optString("operation").orEmpty(),
        startedAt = active?.optLong("started_at") ?: 0,
        phase = progress?.optString("phase").orEmpty(),
        message = progress?.optString("message").orEmpty(),
        done = progress?.optInt("done") ?: 0,
        total = progress?.optInt("total") ?: 0,
        bytesDone = progress?.optLong("bytes_done") ?: 0L,
        bytesTotal = progress?.optLong("bytes_total") ?: 0L,
        lastRun = backupLastRun(o.optJSONObject("finished")),
        run = parseBackupRun(o.optJSONObject("finished")),
        lastBackup = parseBackupRun(o.optJSONObject("last_backup")),
        excludes = if (o.has("excludes")) stringList(o.optJSONArray("excludes")) else null,
        includes = if (o.has("includes")) stringList(o.optJSONArray("includes")) else null,
        auto = parseAutoBackup(o.optJSONObject("auto")),
    )
}

/** The `auto{}` block. Absent means an engine that does not run backups itself. */
private fun parseAutoBackup(o: JSONObject?): AutoBackupStatus? {
    if (o == null) return null
    return AutoBackupStatus(
        enabled = o.optBoolean("enabled"),
        idleStreakSecs = o.optLong("idle_streak_secs"),
        cpuCurrent = o.optDouble("cpu_current", 0.0),
        lastAutoBackupAt = o.optLong("last_auto_backup_at"),
        idleSecs = o.optLong("idle_secs", 300),
        cpuThreshold = o.optDouble("cpu_threshold", 10.0),
        cooldownSecs = o.optLong("cooldown_secs", 1800),
        restoreReady = o.optBoolean("restore_ready", false),
        cpuHistory = o.optJSONArray("cpu_history")?.let { values ->
            (0 until values.length()).map { index -> values.optDouble(index).coerceIn(0.0, 100.0) }
        }.orEmpty(),
    )
}

/**
 * How the last run ended, in one line the phone can show as it is. The engine
 * keeps the slot's result until it is restarted, so this survives closing the
 * chat — the answer to "did it work?" after the progress line has gone.
 */
private fun backupLastRun(finished: JSONObject?): String {
    val summary = finished?.optJSONObject("summary") ?: return ""
    val operation = finished.optString("operation")
    if (!summary.optBoolean("ok")) {
        return "$operation failed: " + summary.optString("error").ifBlank { "unknown reason" }
    }
    val report = summary.optJSONObject("report") ?: return "$operation finished"
    val seconds = report.optLong("duration_ms") / 1000.0
    return if (operation == "restore") {
        // The run went through the whole manifest, so a short tree is not a
        // stopped run: it is the files named here that the bucket could not
        // supply. Say how many, or the line reads as a complete restore.
        val failed = report.optJSONArray("failed")?.length() ?: 0
        "$operation ${report.optString("name")} — ${report.optInt("files")} files written, " +
            "${report.optInt("unchanged")} already correct, %.1fs".format(seconds) +
            if (failed > 0) ", $failed could not be written" else ""
    } else {
        "$operation ${report.optString("name")} — ${report.optInt("files")} files, " +
            "${com.tulipskun.aixodia.data.model.AttachmentRef.humanSize(report.optLong("uploaded_bytes"))}" +
            " uploaded, ${report.optInt("files_reused")} files reused, %.1fs".format(seconds)
    }
}

/**
 * Reads the engine's `finished` slot into the counts a screen can lay out.
 * A slot holding nothing is no run yet; a slot holding an answer with no report
 * is a run that failed, and the reason is all there is.
 */
internal fun parseBackupRun(finished: JSONObject?): BackupRun? {
    if (finished == null) return null
    val summary = finished.optJSONObject("summary")
    val report = summary?.optJSONObject("report")
    return BackupRun(
        operation = finished.optString("operation"),
        ok = summary?.optBoolean("ok") ?: false,
        startedAt = finished.optLong("started_at"),
        finishedAt = finished.optLong("finished_at"),
        error = summary?.optString("error").orEmpty(),
        report = report?.let {
            BackupRunReport(
                name = it.optString("name"),
                root = it.optString("root"),
                bucket = it.optString("bucket"),
                files = it.optInt("files"),
                dirs = it.optInt("dirs"),
                symlinks = it.optInt("symlinks"),
                bytes = it.optLong("bytes"),
                uploadedBytes = it.optLong("uploaded_bytes"),
                filesReused = it.optInt("files_reused"),
                objectsReused = it.optInt("objects_reused"),
                unchanged = it.optInt("unchanged"),
                deletedManifests = it.optInt("deleted_manifests"),
                skipped = stringList(it.optJSONArray("skipped")),
                failed = stringList(it.optJSONArray("failed")),
                durationMs = it.optLong("duration_ms"),
            )
        },
    )
}

private fun stringList(arr: JSONArray?): List<String> {
    if (arr == null) return emptyList()
    val out = ArrayList<String>(arr.length())
    for (i in 0 until arr.length()) {
        val s = arr.optString(i)
        if (s.isNotBlank()) out.add(s)
    }
    return out
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
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build(),
    private val clientToken: ClientToken = ClientToken.passThrough,
) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    // History, settings and provider data all go through the Engine API over
    // the stable Named Tunnel; Android never calls Cloudflare D1 directly.
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
     * A config snapshot whose [ConnConfig.token] is the already-issued Engine
     * session. GitHub OAuth is used only during sign-in; no Cloudflare
     * credential is used by this app.
     */
    private suspend fun engineConfig(): ConnConfig {
        val raw = settings.current()
        return raw.copy(
            token = clientToken.get(raw.sessionToken, raw.daemonUrl),
        )
    }

    /**
     * Builds an absolute URL, or null when the app has no endpoint configured
     * yet. A fresh install has empty settings by design, and that must look
     * like "not configured", never like a crash: okhttp throws on a relative
     * URL, and that exception used to escape into the ViewModel scope.
     */
    /** Asks the running daemon to end this session and start the next one. */
    suspend fun triggerPushNow(): Boolean = withContext(Dispatchers.IO) {
        val c = engineConfig()
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
        val c = engineConfig()
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
     * Every chat the engine knows, newest first. When the Engine is offline,
     * the repository keeps showing the already synchronized Room cache.
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
        daemonSessions()
    }

    /** The tunnel read of the chat list. Null when it cannot be dialled. */
    private suspend fun daemonSessions(): List<SessionRow>? = withContext(Dispatchers.IO) {
        val c = engineConfig()
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

    /**
     * Creates the chat on the daemon and returns its id, or "" on failure.
     * [id] is a locally-created draft id when the phone is promoting a New
     * chat on its first send; the daemon keeps that id so no local rows have
     * to move. Blank asks the daemon to generate one.
     */
    suspend fun createSession(id: String, title: String): String = withContext(Dispatchers.IO) {
        val c = engineConfig()
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
        val c = engineConfig()
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
        val c = engineConfig()
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
     * One page of history, oldest-first, read from the Engine. Offline pages
     * remain available from the local Room cache.
     */
    suspend fun turns(sessionId: String, beforeSeq: Long, limit: Int = 50): List<TurnRow> =
        withContext(Dispatchers.IO) {
            // A failed fallback is not an authoritative empty page. Let paging
            // retain its older-history marker and offer another attempt.
            val rows = daemonTurns(sessionId, beforeSeq, limit).orEmpty()
            pageTurns(rows, beforeSeq, limit)
        }

    /**
     * The Engine read of one page. Null when the daemon is not configured or
     * the request failed. An older engine that ignores the page parameters
     * still sends everything; pageTurns cuts that down.
     */
    private suspend fun daemonTurns(sessionId: String, beforeSeq: Long, limit: Int): List<TurnRow>? =
        withContext(Dispatchers.IO) {
            val c = engineConfig()
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
        val c = engineConfig()
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
        val c = engineConfig()
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
        val c = engineConfig()
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
        val c = engineConfig()
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
        val c = engineConfig()
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
        val c = engineConfig()
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
        val c = engineConfig()
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
        val c = engineConfig()
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
        val c = engineConfig()
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
        val c = engineConfig()
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
        val c = engineConfig()
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
        val c = engineConfig()
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
     * long-lived API token out of a URL that a proxy or log may keep.
     * [authToken] is the short-lived token TerminalSocket resolves; when a caller
     * leaves it null the engine config resolves it internally. */
    suspend fun terminalTicket(authToken: String? = null): String? = withContext(Dispatchers.IO) {
        val c = engineConfig()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext null
        runCatching {
            val req = Request.Builder().url("$base/v1/terminals/ticket")
                .header("Authorization", "Bearer ${authToken ?: c.token}")
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
        val c = engineConfig()
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
        val c = engineConfig()
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
        val c = engineConfig()
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
        val c = engineConfig()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext 0
        runCatching {
            val req = Request.Builder().url("$base/v1/sessions")
                .header("Authorization", "Bearer ${c.token}")
                .header("Accept", "application/json")
                .get().build()
            client.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw IllegalStateException("Engine HTTP ${r.code}: $text")
                val env = runCatching { JSONObject(text) }.getOrNull()
                    ?: throw IllegalStateException("Engine returned an invalid response")
                env.optJSONArray("sessions")?.length()
                    ?: throw IllegalStateException("Engine response has no sessions list")
            }
        }.getOrElse { throw it }
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
        val c = engineConfig()
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
     * The engine's own backup, driven from here. `GET /api/backup` answers what
     * is configured, what is running and how the last run ended; nothing in the
     * reply is a secret — the hub token comes back as its last four characters
     * at most, and the phone never sees it whole.
     */
    suspend fun backupStatus(): BackupStatus? = withContext(Dispatchers.IO) {
        val c = engineConfig()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext null
        runCatchingCancellable {
            val req = Request.Builder().url("$base/api/backup")
                .header("Authorization", "Bearer ${c.token}")
                .header("Accept", "application/json")
                .get().build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                parseBackupStatus(JSONObject(r.body!!.string()))
            }
        }.getOrNull()
    }

    /**
     * Starts a backup. The engine answers 202 and keeps working on its own
     * thread, so this returns at once and progress is read from [backupStatus].
     * A busy engine answers 409 with its own reason, which is what shows here.
     */
    suspend fun backupStart(): String = withContext(Dispatchers.IO) {
        val c = engineConfig()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext "Daemon URL not configured"
        runCatchingCancellable {
            val req = Request.Builder().url("$base/api/backup")
                .header("Authorization", "Bearer ${c.token}")
                .header("Content-Type", "application/json")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { r ->
                failure("Backup started", r).let { if (r.isSuccessful) it else "Backup failed: $it" }
            }
        }.getOrDefault("Backup failed")
    }

    /**
     * Restores the bucket's backup — the engine keeps one current tree, not a
     * history. The engine refuses anything that does not say `confirm: true`,
     * because a restore overwrites files under its root — the caller has
     * already asked the reader, so this always says yes.
     */
    suspend fun backupRestore(): String = withContext(Dispatchers.IO) {
        val c = engineConfig()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext "Daemon URL not configured"
        val body = JSONObject().put("confirm", true).toString()
        runCatchingCancellable {
            val req = Request.Builder().url("$base/api/backup/restore")
                .header("Authorization", "Bearer ${c.token}")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { r ->
                failure("Restore started", r).let { if (r.isSuccessful) it else "Restore failed: $it" }
            }
        }.getOrDefault("Restore failed")
    }

    /**
     * Saves the backup configuration on the engine, which keeps it in D1.
     * A blank [token] is not sent at all: the stored one stays, so changing the
     * bucket never means retyping the key, and a key the phone no longer shows
     * cannot be wiped by accident. A null [excludes] is likewise not sent, and
     * an empty list is — the two mean "leave it alone" and "exclude nothing".
     */
    suspend fun saveBackupConfig(
        token: String,
        bucket: String,
        prefix: String,
        excludes: List<String>? = null,
        includes: List<String>? = null,
        root: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val c = engineConfig()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext "Daemon URL not configured"
        val body = JSONObject().apply {
            if (token.isNotBlank()) put("token", token.trim())
            if (bucket.isNotBlank()) put("bucket", bucket.trim())
            if (prefix.isNotBlank()) put("prefix", prefix.trim())
            root?.takeIf { it.isNotBlank() }?.let { put("root", it.trim()) }
            excludes?.let { put("excludes", JSONArray(it)) }
            includes?.let { put("includes", JSONArray(it)) }
        }.toString()
        runCatchingCancellable {
            val req = Request.Builder().url("$base/api/backup/config")
                .header("Authorization", "Bearer ${c.token}")
                .header("Content-Type", "application/json")
                .put(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { r ->
                failure("Backup settings saved", r).let { if (r.isSuccessful) it else "Save failed: $it" }
            }
        }.getOrDefault("Save failed")
    }

    /**
     * Saves the automatic backup's thresholds. Sent apart from the rest of the
     * config because the engine merges a write field by field: a tap on a chip
     * changes the idle window and nothing else, and cannot reach the hub token or
     * the scope by accident.
     */
    suspend fun saveBackupAuto(
        enabled: Boolean,
        idleSecs: Long,
        cpuThreshold: Double,
        cooldownSecs: Long,
    ): String = withContext(Dispatchers.IO) {
        val c = engineConfig()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext "Daemon URL not configured"
        val body = JSONObject()
            .put("auto_enabled", enabled)
            .put("auto_idle_secs", idleSecs)
            .put("auto_cpu_threshold", cpuThreshold)
            .put("auto_cooldown_secs", cooldownSecs)
            .toString()
        runCatchingCancellable {
            val req = Request.Builder().url("$base/api/backup/config")
                .header("Authorization", "Bearer ${c.token}")
                .header("Content-Type", "application/json")
                .put(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { r ->
                failure("Auto-backup saved", r).let { if (r.isSuccessful) it else "Save failed: $it" }
            }
        }.getOrDefault("Save failed")
    }

    /**
     * Lists one folder on the engine, for the picker. Read-only and stateless —
     * it stores nothing and takes no job slot, so it answers while a run is
     * configured but idle and while one is in flight.
     */
    suspend fun backupBrowse(path: String): ScopeRead<BackupBrowse> = withContext(Dispatchers.IO) {
        val c = engineConfig()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext ScopeRead(error = "Daemon URL not configured")
        val url = "$base/api/backup/browse?path=" + URLEncoder.encode(path, "UTF-8")
        runCatchingCancellable {
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer ${c.token}")
                .header("Accept", "application/json")
                .get().build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use ScopeRead(error = failure("browse", r))
                val body = runCatching { JSONObject(r.body!!.string()) }.getOrNull()
                    ?: return@use ScopeRead(error = "the engine sent an unreadable listing")
                ScopeRead(value = parseBackupBrowse(body))
            }
        }.getOrDefault(ScopeRead(error = "The engine did not answer"))
    }

    /**
     * Asks what a scope would cover, before it is saved or run. The draft fields
     * are what is on screen, not what is in D1: null means "use what the engine
     * already stores", so the preview answers the question the picker is asking
     * without making it true first.
     */
    suspend fun backupPreview(
        root: String? = null,
        includes: List<String>? = null,
        excludes: List<String>? = null,
    ): ScopeRead<BackupPreview> = withContext(Dispatchers.IO) {
        val c = engineConfig()
        val base = absoluteUrl(c.daemonUrl) ?: return@withContext ScopeRead(error = "Daemon URL not configured")
        val body = JSONObject().apply {
            root?.takeIf { it.isNotBlank() }?.let { put("root", it.trim()) }
            includes?.let { put("includes", JSONArray(it)) }
            excludes?.let { put("excludes", JSONArray(it)) }
        }.toString()
        runCatchingCancellable {
            val req = Request.Builder().url("$base/api/backup/preview")
                .header("Authorization", "Bearer ${c.token}")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use ScopeRead(error = failure("preview", r))
                val parsed = runCatching { JSONObject(r.body!!.string()) }.getOrNull()
                    ?: return@use ScopeRead(error = "the engine sent an unreadable preview")
                ScopeRead(value = parseBackupPreview(parsed))
            }
        }.getOrDefault(ScopeRead(error = "The engine did not answer"))
    }

    /**
     * Resolves the Cloudflare account and database from the token and reports
     * what was found, so the settings screen can show it and remember it
     * Throws with Cloudflare's own message when it cannot.
     */
    /** Checks the configured Engine directly; no Cloudflare API or KV access. */
    suspend fun node(): NodeInfo? = withContext(Dispatchers.IO) {
        val base = absoluteUrl(settings.current().daemonUrl) ?: return@withContext null
        runCatchingCancellable {
            val req = Request.Builder().url("$base/healthz")
                .header("Accept", "application/json")
                .get().build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = JSONObject(response.body?.string().orEmpty())
                NodeInfo(
                    tunnelUrl = base,
                    version = body.optString("version"),
                    online = body.optBoolean("ok", true),
                    startedAt = body.optLong("started"),
                    bootAt = body.optLong("boot"),
                )
            }
        }.getOrNull()
    }


}
