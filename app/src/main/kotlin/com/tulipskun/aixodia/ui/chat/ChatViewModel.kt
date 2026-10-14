package com.tulipskun.aixodia.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.os.SystemClock
import com.tulipskun.aixodia.SettingsStore
import com.tulipskun.aixodia.data.model.AiOutput
import com.tulipskun.aixodia.data.model.AttachmentRef
import com.tulipskun.aixodia.data.model.ProviderStatus
import com.tulipskun.aixodia.data.model.ProviderView
import com.tulipskun.aixodia.data.model.ToolStep
import com.tulipskun.aixodia.data.remote.ConnState
import com.tulipskun.aixodia.data.repo.ChatRepository
import com.tulipskun.aixodia.ui.display.TokenField
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/** One sub agent the daemon is running (or just finished) for this chat. */
data class SubAgentActivity(
    val jobId: String,
    val label: String,
    val detail: String,
    val startedAtMs: Long,
    val running: Boolean,
    val stopping: Boolean = false,
) {
    fun elapsedMs(nowMs: Long): Long = (nowMs - startedAtMs).coerceAtLeast(0L)
}

/**
 * What the footer shows about the turn on screen: which model, how many tokens
 * it used, how long it took and how fast. Counts are exact once the provider
 * reports them and an estimate (marked with ≈) while the answer is still
 * streaming, because a phone that shows nothing until the turn ends is not a
 * live view of anything.
 */
data class TurnStats(
    val model: String = "",
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheRead: Int = 0,
    val cacheWrite: Int = 0,
    // ReasoningTokens is output the model spent thinking. InputIncludesCache
    // says whether inputTokens already contains cacheRead, so the footer can
    // label the two numbers instead of printing them as rivals.
    val reasoningTokens: Int = 0,
    val inputIncludesCache: Boolean = false,
    val exact: Boolean = false,
    val startedAtMs: Long = 0L,
    /** When the first token actually arrived, which is where the rate starts. */
    val firstTokenAtMs: Long = 0L,
    val endedAtMs: Long = 0L,
    val estimatedTokens: Int = 0,
    val running: Boolean = false,
) {
    fun elapsedMs(nowMs: Long): Long {
        val end = if (endedAtMs > 0L) endedAtMs else nowMs
        return (end - startedAtMs).coerceAtLeast(0L)
    }

    fun outputTokensNow(): Int = if (exact) outputTokens else estimatedTokens

    /** The prompt that was not served from cache, under the provider's convention. */
    fun freshInputTokens(): Int =
        if (inputIncludesCache) (inputTokens - cacheRead).coerceAtLeast(0) else inputTokens

    /** The whole prompt however the provider chose to split it. */
    fun totalInputTokens(): Int =
        if (inputIncludesCache) inputTokens else inputTokens + cacheRead + cacheWrite

    /**
     * Tokens per second, measured from the first token rather than from the
     * moment the question was sent. Time spent waiting for the provider and
     * between tool rounds is not generation time; counting it made every answer
     * read as "1t/s" no matter how fast the model actually wrote.
     */
    fun tokensPerSecond(nowMs: Long): Double {
        val start = if (firstTokenAtMs > 0L) firstTokenAtMs else startedAtMs
        val end = if (endedAtMs > 0L) endedAtMs else nowMs
        val millis = (end - start).coerceAtLeast(0L)
        if (millis <= 0L) return 0.0
        return outputTokensNow().toDouble() * 1000.0 / millis.toDouble()
    }
}

class ChatViewModel(
    private val repo: ChatRepository,
    private val settings: SettingsStore,
    initialSession: String,
    // The application context only, to open a picked file's stream for upload.
    private val appContext: android.content.Context? = null,
) : ViewModel() {
    /** One thing a live turn said or did, in the order it happened. */
    sealed interface LiveBlock {
        /** The model's words before a tool round, as its own block. */
        data class Say(val text: String, val atMs: Long) : LiveBlock
        /** A tool step: the call, then its result when it lands. */
        data class Act(val step: ToolStep) : LiveBlock
    }

    /**
     * Puts [step] in the live timeline, replacing the card of the same call when
     * one is already there: a call that reports again while it runs must not grow
     * a second card, and a turn that runs the same tool four times must keep
     * four.
     */
    private fun upsertStep(blocks: List<LiveBlock>, step: ToolStep): List<LiveBlock> {
        val at = blocks.indexOfFirst { it is LiveBlock.Act && it.step.key == step.key }
        if (at < 0) return blocks + LiveBlock.Act(step)
        val out = blocks.toMutableList()
        out[at] = LiveBlock.Act(step)
        return out
    }

    /** The chat currently on screen; set by ensureSession, like a chat app. */
    var sessionId: String = initialSession
        private set

    val messages = repo.observeMessages(sessionId)
    val sessions = repo.observeSessions()
    val conn: StateFlow<ConnState> = repo.connState

    /** Why the socket is down, straight from the daemon's HTTP answer. */
    val socketError = repo.socketError

    /**
     * The footer's field list for the provider in use. Switched on the endpoint
     * rather than read once, because settings.current() is suspend and the route
     * can change under a live socket.
     */
    val tokenFields: Flow<List<TokenField>> =
        settings.endpointFlow.flatMapLatest { settings.tokenFieldsFlow(it) }

    /** Latest live status line for the thread ("sub: web_fetch …"). */
    val status = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val notice = MutableStateFlow("")

    /**
     * The answer as it streams in, before D1 has it. Kept out of the database
     * on purpose: the daemon writes one authoritative row per turn, and the
     * thread is refreshed from there when the turn closes, so a streamed bubble
     * can never become a duplicate row.
     */
    val liveText = MutableStateFlow("")
    val liveAgent = MutableStateFlow("main")
    /**
     * What the live turn has said and done, in the order it happened: a piece of
     * narration, then the tool it ran, then the next piece. One list, keyed by
     * call id, is what makes the live thread look like the stored thread it
     * becomes — keyed by the tool's name instead, a turn that ran the same tool
     * four times kept only the last card, and the cards all sat under the words
     * instead of beside them, so the screen reshuffled the moment the rows came
     * back from the daemon.
     */
    val liveBlocks = MutableStateFlow<List<LiveBlock>>(emptyList())
    /** Recorded thinking time for the current reasoning event, if the daemon sent one. */
    val liveThinkingMs = MutableStateFlow(0L)
    /** Which agent that thinking event belongs to; raw reasoning never leaves the daemon. */
    val liveThinkingAgent = MutableStateFlow("main")
    val providers = MutableStateFlow<List<ProviderView>>(emptyList())
    /** Same catalogue's global health/key counts, shown as a caption in the session sheet. */
    val providerStatuses = MutableStateFlow<List<ProviderStatus>>(emptyList())

    /** The sub agents this turn started, newest last, each with its own stop. */
    val subAgents = MutableStateFlow<List<SubAgentActivity>>(emptyList())

    /** Model, tokens, elapsed time and rate for the footer. */
    val turnStats = MutableStateFlow(TurnStats())
    /** Unix second the daemon process started; 0 = unknown/not polled yet. */
    val daemonStartedAt = MutableStateFlow(0L)
    /** Files picked and uploaded, waiting to go out with the next message. */
    val pendingAttachments = MutableStateFlow<List<AttachmentRef>>(emptyList())
    /** True while an upload is in flight, so the picker can be disabled. */
    val uploading = MutableStateFlow(false)
    /** The last text sent, kept so a failed turn can be retried verbatim. */
    private var lastSentText = ""
    private var lastAttachments: List<AttachmentRef> = emptyList()
    /** The error to show as a retryable card, or "" when nothing failed. */
    val errorCard = MutableStateFlow("")
    /**
     * True once the daemon's closing message has replaced the streamed text.
     * A delta arriving after that would be appended to the finished answer and
     * read as a repetition, so it is dropped.
     */
    private var answerSettled = false
    private var liveSubText = ""
    private var liveChars = 0
    val selectedProvider = MutableStateFlow("")
    val selectedModel = MutableStateFlow("")
    /** Whether the open chat has a stored pin, as opposed to a draft selection. */
    val hasStoredRoute = MutableStateFlow(false)

    init {
        // A fresh install has no settings yet, and every call here is network
        // facing: failures belong in [notice], never as an uncaught exception
        // (an uncaught one in viewModelScope takes the whole process down).
        viewModelScope.launch {
            runCatching {
                repo.syncSessions()
                // No session id to type: open the last chat, or start one.
                val id = repo.ensureSession(initialSession)
                sessionId = id
                settings.saveSession(id)
            }.onFailure { err ->
                notice.value = "เชื่อมต่อไม่ได้: ${err.message ?: err::class.simpleName}"
            }
            // The header shows the route this chat actually uses.
            runCatching { repo.sessionRoute(sessionId) }.getOrNull()?.let { (provider, model) ->
                selectedProvider.value = provider
                selectedModel.value = model
                hasStoredRoute.value = true
            }
        }
        // The start time comes from the daemon's D1 row, read once (and again
        // whenever the socket comes back). Uptime is then counted on the phone,
        // so the header ticks without a single periodic network call. The last
        // one is kept, so a header that starts blank for the length of one query
        // still shows a number the moment the app opens; the query corrects it.
        viewModelScope.launch {
            settings.daemonStartedAtFlow.first().let { cached ->
                if (cached > 0L) daemonStartedAt.value = cached
            }
            refreshDaemonStart()
        }
        viewModelScope.launch {
            repo.connState.collect { if (it == ConnState.ONLINE) refreshDaemonStart() }
        }
        viewModelScope.launch {
            repo.liveFrames.collect { f ->
                if (f.sessionId.isNotEmpty() && f.sessionId != sessionId) return@collect
                when (f.kind) {
                    // One streamed chunk: append to the bubble the phone is
                    // already showing, or to the sub agent's status line.
                    "delta" -> onDelta(f.agent, f.text, f)
                    // The authoritative answer. A streamed turn already filled
                    // the bubble, so this only confirms it.
                    "message" -> onMessage(f.agent, f.text, f)
                    "trace" -> onTrace(f)
                    "done" -> onTurnDone(f)
                    "error" -> {
                        status.value = ""
                        liveText.value = ""
                        liveBlocks.value = emptyList()
                        liveThinkingMs.value = 0L
                        busy.value = false
                        // Raw daemon text is logged by the daemon; the user sees Thai.
                        // The error becomes a retryable card, not just a notice line.
                        val msg = friendlyError(f.text)
                        errorCard.value = msg
                        notice.value = msg
                        finishStats()
                    }
                    // The ack only means the message reached the daemon. The
                    // turn keeps running until `done`, and until then the stop
                    // button must stay on screen.
                    "ack" -> { notice.value = "" }
                }
            }
        }
    }

    private fun onDelta(agent: String, chunk: String, frame: AiOutput? = null) {
        if (chunk.isEmpty() || answerSettled) return
        liveThinkingMs.value = 0L
        if (agent.isNotEmpty() && agent != "main") {
            status.value = "${if (agent == "sub") "sub" else agent}: " + (liveSubText + chunk).take(80)
            liveSubText += chunk
            noteSubAgent(frame, chunk.take(80))
            return
        }
        liveAgent.value = agent.ifEmpty { "main" }
        liveText.value += chunk
        liveChars += chunk.length
        // The clock for the rate starts here, not when the question was sent.
        val stats = turnStats.value
        if (stats.firstTokenAtMs == 0L) {
            turnStats.value = stats.copy(firstTokenAtMs = SystemClock.elapsedRealtime())
        }
        estimateTokens()
        busy.value = true
    }

    /**
     * While the answer streams, the exact token count is still unknown: the
     * provider only reports it at the end. Four characters per token is the
     * usual rule of thumb, and the footer marks it with ≈ so nobody reads it as
     * a bill.
     */
    private fun estimateTokens() {
        val stats = turnStats.value
        if (stats.exact) return
        turnStats.value = stats.copy(estimatedTokens = (liveChars + 3) / 4)
    }

    private fun onMessage(agent: String, text: String, frame: AiOutput? = null) {
        if (text.isBlank()) return
        if (agent.isNotEmpty() && agent != "main") {
            status.value = "${if (agent == "sub") "sub" else agent}: " + text.take(80)
            liveSubText = text
            noteSubAgent(frame, text.take(80))
            return
        }
        // Replace whatever streamed in with the daemon's own copy: it is the one
        // that also lands in D1.
        answerSettled = true
        liveText.value = text
        liveThinkingMs.value = 0L
        recordUsage(frame)
        busy.value = true
    }

    /**
     * The daemon reports the real token counts on the final message. From then
     * on the footer shows those numbers instead of the estimate, and the rate is
     * computed from them.
     */
    private fun recordUsage(frame: AiOutput?) {
        if (frame == null) return
        val input = frame.inputTokens
        val output = frame.outputTokens
        val cacheRead = frame.cacheRead
        val cacheWrite = frame.cacheWrite
        val stats = turnStats.value
        // The closing frame names the model that actually answered and how long
        // it took, which is what the footer of that message says (AX-095). The
        // route stays as the fallback while the answer is still streaming.
        val model = frame.model.ifBlank { stats.model }
        if (input <= 0 && output <= 0 && cacheRead <= 0 && cacheWrite <= 0 && frame.reasoningTokens <= 0) {
            if (model != stats.model) turnStats.value = stats.copy(model = model)
            return
        }
        // The clock deliberately still starts when the user pressed send: the
        // figure next to the tokens is how long they waited, not how long the
        // provider wrote.
        val started = stats.startedAtMs
        turnStats.value = stats.copy(
            model = model,
            inputTokens = input,
            outputTokens = output,
            cacheRead = cacheRead,
            cacheWrite = cacheWrite,
            reasoningTokens = frame.reasoningTokens,
            inputIncludesCache = frame.inputIncludesCache,
            exact = true,
            estimatedTokens = output,
            startedAtMs = started,
            endedAtMs = if (stats.endedAtMs > 0L) stats.endedAtMs else SystemClock.elapsedRealtime(),
            running = false,
        )
    }

    private fun onTrace(f: AiOutput) {
        if (f.agent.isNotEmpty() && f.agent != "main") {
            val detail = when (f.stage) {
                "tool_call", "tool_running" -> "กำลังใช้ ${f.toolCall?.name ?: "เครื่องมือ"}"
                "tool_result" -> if (f.toolResult?.isError == true) "เครื่องมือล้มเหลว" else "เครื่องมือเสร็จแล้ว"
                "request" -> "กำลังส่งงาน"
                "response_text" -> "กำลังคิด"
                "retry_wait" -> "รอสลองใหม่"
                else -> f.text.ifEmpty { f.stage }
            }
            noteSubAgent(f, detail)
        }
        when (f.stage) {
            "tool_call", "tool_running" -> {
                val name = f.toolCall?.name ?: return
                liveThinkingMs.value = 0L
                // What came before this tool is its own turn, now and after.
                val said = liveText.value.trim()
                if (said.isNotEmpty()) {
                    liveBlocks.value = liveBlocks.value +
                        LiveBlock.Say(said, System.currentTimeMillis())
                    liveText.value = ""
                }
                liveBlocks.value = upsertStep(liveBlocks.value, ToolStep(
                        id = f.toolCall?.id.orEmpty(),
                        name = name,
                        args = f.toolCall?.arguments.orEmpty(),
                        durationMs = f.toolDurationMs,
                    ))
                status.value = "$name…"
            }
            "tool_result" -> {
                val name = f.toolResult?.name ?: f.toolCall?.name ?: return
                val result = f.toolResult?.text.orEmpty()
                liveThinkingMs.value = 0L
                // The result names the call it answers. Steps are keyed by that
                // call id, not by the tool's name: a turn that runs the same tool
                // four times has four steps, and replacing the previous one by name
                // left the first three with no card at all.
                val resultID = f.toolResult?.id.orEmpty()
                val callID = f.toolCall?.id.orEmpty()
                val key = resultID.ifBlank { callID.ifBlank { name } }
                // A result frame names the tool and its output, never its
                // arguments, so the step it completes has to lend them over.
                // Rebuilding it from the frame alone is how a live step lost the
                // command it was running and showed a bare "bash · 2.6s" while
                // the same step in the stored thread showed the whole line.
                val known = liveBlocks.value.filterIsInstance<LiveBlock.Act>()
                    .firstOrNull { it.step.key == key }?.step?.args.orEmpty()
                liveBlocks.value = liveBlocks.value.map { block ->
                    if (block is LiveBlock.Act && block.step.key == key) {
                        block.copy(
                            step = block.step.copy(
                                args = f.toolCall?.arguments.orEmpty().ifBlank { known },
                                result = result.take(160),
                                isError = f.toolResult?.isError ?: false,
                                done = true,
                                durationMs = f.toolDurationMs,
                            )
                        )
                    } else {
                        block
                    }
                }
                status.value = if (f.toolResult?.isError == true) "$name ล้มเหลว" else "$name เสร็จแล้ว"
            }
            "response_text" -> {
                liveThinkingMs.value = f.reasoningMs
                liveThinkingAgent.value = f.agent.ifEmpty { "main" }
                status.value = if (f.reasoningMs > 0) {
                    "กำลังคิด · " + String.format(Locale.US, "%.1fs", f.reasoningMs / 1000.0)
                } else {
                    "กำลังคิด…"
                }
            }
            "retry_wait" -> status.value = "รอสลองใหม่…"
            "request" -> status.value = "กำลังส่งให้ provider…"
            "provider_ready" -> status.value = "provider รับแล้ว กำลังประมวลผล"
            else -> status.value = f.text.ifEmpty { f.stage }
        }
    }

    private fun onTurnDone(frame: AiOutput) {
        val stage = frame.stage
        // A cancel that named a job answers for that job only: the turn keeps
        // running, so the busy state and the stream stay as they are.
        if (stage.startsWith("subagent_")) {
            onSubAgentStopped(frame)
            return
        }
        if (stage == "cancelled") {
            notice.value = "หยุดการทำงานแล้ว"
        } else if (stage == "already_done") {
            notice.value = "งานนั้นจบไปแล้ว"
        }
        status.value = ""
        liveSubText = ""
        liveBlocks.value = emptyList()
        liveThinkingMs.value = 0L
        busy.value = false
        answerSettled = false
        subAgents.value = subAgents.value.map { it.copy(running = false) }
        finishStats()
        // The daemon mirrored the turn into D1; pull it so live bubbles are
        // replaced by the stored rows instead of a second copy. The streamed
        // text and tool steps stay on screen until the stored rows are ready —
        // clearing them first makes the answer flicker out and back (AXCH-026).
        // Refresh runs even for tool-only turns (no streamed text): otherwise
        // their rows never arrive until the next open.
        viewModelScope.launch {
            runCatching { repo.refreshLatest(sessionId) }
            liveText.value = ""
            liveBlocks.value = emptyList()
        }
    }

    /** Freezes the footer's numbers when the turn ends. */
    private fun finishStats() {
        val stats = turnStats.value
        if (stats.startedAtMs == 0L) return
        turnStats.value = stats.copy(running = false, endedAtMs = SystemClock.elapsedRealtime())
    }

    /**
     * Keeps one row per sub agent: what it is doing, how long it has been at it,
     * and whether it is still running. The phone is the only place the user can
     * see that work at all, so the row is built from the frames, not guessed.
     */
    private fun noteSubAgent(frame: AiOutput?, detail: String) {
        val jobId = frame?.jobId?.takeIf { it.isNotBlank() } ?: return
        val now = SystemClock.elapsedRealtime()
        val rows = subAgents.value
        val existing = rows.firstOrNull { it.jobId == jobId }
        val row = if (existing == null) {
            SubAgentActivity(jobId = jobId, label = "sub", detail = detail, startedAtMs = now, running = true)
        } else {
            existing.copy(detail = detail, running = true, stopping = false)
        }
        subAgents.value = if (existing == null) rows + row else rows.map { if (it.jobId == jobId) row else it }
    }

    private fun onSubAgentStopped(frame: AiOutput) {
        val jobId = frame.jobId.takeIf { it.isNotBlank() } ?: return
        val rows = subAgents.value
        when (frame.stage) {
            "subagent_stopping" -> {
                subAgents.value = rows.map { if (it.jobId == jobId) it.copy(stopping = true) else it }
                status.value = "กำลังหยุด sub agent…"
            }
            "subagent_not_found" -> {
                subAgents.value = rows.filterNot { it.jobId == jobId }
                notice.value = "ไม่พบ sub agent ที่หยุด — อาจจบไปแล้ว"
            }
            "subagent_stop_failed" -> {
                subAgents.value = rows.map { if (it.jobId == jobId) it.copy(stopping = false) else it }
                notice.value = "หยุด sub agent ไม่สำเร็จ"
            }
            // The daemon says how each worker ended. A row that only ever saw
            // its own deltas would keep claiming it is still running.
            "subagent_stopped", "subagent_completed", "subagent_failed" -> {
                val ended = frame.stage.removePrefix("subagent_")
                subAgents.value = rows.map {
                    if (it.jobId != jobId) it
                    else it.copy(
                        running = false,
                        stopping = false,
                        detail = when (ended) {
                            "stopped" -> "หยุดแล้ว"
                            "failed" -> "ล้มเหลว"
                            else -> "เสร็จแล้ว"
                        },
                    )
                }
            }
        }
    }

    /**
     * The per-row stop: one worker stops, the turn that delegated to it keeps
     * going. The daemon answers for the job, and that answer is what moves the
     * row, so the button never has to guess.
     */
    fun stopSubAgent(jobId: String) {
        if (jobId.isBlank()) return
        if (!repo.stopSubAgent(sessionId, jobId)) {
            notice.value = "ส่งคำสั่งหยุด sub agent ไม่ได้ — socket ไม่ออนไลน์"
            return
        }
        subAgents.value = subAgents.value.map { if (it.jobId == jobId) it.copy(stopping = true) else it }
        status.value = "กำลังหยุด sub agent…"
    }

    /**
     * The stop button. The daemon answers with a done frame whose stage says
     * whether it stopped something, so the phone can say so honestly instead of
     * pretending the turn finished.
     */
    fun stop() {
        if (!repo.stopTurn(sessionId)) {
            notice.value = "ยังหยุดไม่ได้ — socket ไม่ออนไลน์"
            return
        }
        status.value = "กำลังหยุด…"
    }

    /** Loads the catalogue the picker offers, once the daemon is online. */
    fun loadProviders() {
        viewModelScope.launch {
            val list = runCatching { repo.models() }.getOrDefault(emptyList())
            providers.value = list
            providerStatuses.value = runCatching { repo.providerStatuses() }.getOrDefault(emptyList())
            // Start from what this chat already uses: the picker must not look
            // like a pending change just because it was opened.
            if (selectedProvider.value.isBlank()) {
                runCatching { repo.sessionRoute(sessionId) }.getOrNull()?.let { (provider, model) ->
                    val view = list.firstOrNull { it.id == provider }
                    selectedProvider.value = provider
                    selectedModel.value = model.takeIf { m -> view?.models?.any { it.id == m } == true }
                        ?: view?.defaultModel.orEmpty()
                    hasStoredRoute.value = true
                }
            }

            // No stored route means this chat follows the daemon's agent default:
            // the picker must not invent one and make it look already chosen.
        }
    }

    private fun pick(provider: ProviderView) {
        selectedProvider.value = provider.id
        selectedModel.value = selectedModel.value
            .takeIf { id -> provider.models.any { it.id == id } }
            ?: provider.defaultModel.ifEmpty { provider.models.firstOrNull()?.id.orEmpty() }
    }

    fun chooseProvider(id: String) {
        providers.value.firstOrNull { it.id == id }?.let { pick(it) }
    }

    fun chooseModel(id: String) { selectedModel.value = id }

    /** Saves the chosen provider/model for the open chat. */
    fun saveModel() {
        val provider = selectedProvider.value
        val model = selectedModel.value
        if (provider.isBlank() || model.isBlank()) {
            notice.value = "เลือก provider และ model ก่อน"
            return
        }
        viewModelScope.launch {
            val ok = runCatching { repo.setSessionModel(sessionId, provider, model) }.getOrDefault(false)
            if (ok) {
                hasStoredRoute.value = true
                notice.value = "ใช้ $provider / $model กับแชทนี้แล้ว"
            } else {
                notice.value = "บันทึก provider/model ไม่สำเร็จ"
            }
        }
    }

    /** Returns the open chat to the global agent defaults and forgets its pin. */
    fun clearModel() {
        if (!hasStoredRoute.value) {
            notice.value = "แชทนี้ใช้ค่าของ agent อยู่แล้ว"
            return
        }
        viewModelScope.launch {
            val ok = runCatching { repo.clearSessionModel(sessionId) }.getOrDefault(false)
            if (ok) {
                selectedProvider.value = ""
                selectedModel.value = ""
                hasStoredRoute.value = false
                notice.value = "ยกเลิกการล็อกโมเดลของแชทนี้แล้ว — ใช้ค่าของ agent"
            } else {
                notice.value = "ยกเลิกการล็อกโมเดลไม่สำเร็จ"
            }
        }
    }

    /**
     * Uploads the picked files to the daemon, one after another. The daemon
     * answers with the path it stored for each, and every path rides along with
     * the message so the agent can read them. Uploads are sequential on purpose:
     * ten files picked at once would otherwise open ten connections at once on a
     * phone that is already streaming the answer.
     */
    fun attach(uris: List<android.net.Uri>) {
        val ctx = appContext ?: return
        if (uris.isEmpty()) return
        uploading.value = true
        viewModelScope.launch {
            val resolver = ctx.contentResolver
            var failed = 0
            for (uri in uris) {
                var name = "file"
                var mime = ""
                runCatching {
                    resolver.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
                    }
                    mime = resolver.getType(uri).orEmpty()
                }
                val ref = repo.uploadAttachment(sessionId, name, mime) {
                    runCatching { resolver.openInputStream(uri) }.getOrNull()
                }
                if (ref == null) failed++ else pendingAttachments.value = pendingAttachments.value + ref
            }
            if (failed > 0) {
                notice.value = "อัปโหลดไม่สำเร็จ $failed จาก ${uris.size} ไฟล์"
            }
            uploading.value = false
        }
    }

    fun removeAttachment(index: Int) {
        pendingAttachments.value = pendingAttachments.value.filterIndexed { i, _ -> i != index }
    }

    fun send(text: String) {
        val attachments = pendingAttachments.value
        if (text.isBlank() && attachments.isEmpty()) return
        answerSettled = false
        lastSentText = text.trim()
        lastAttachments = attachments
        errorCard.value = ""
        busy.value = true
        liveChars = 0
        liveSubText = ""
        liveBlocks.value = emptyList()
        subAgents.value = emptyList()
        // The footer opens with this turn: which model answers, from when, and
        // nothing else yet. A chat that follows the agent's default says so —
        // printing whatever route happens to be current later would put another
        // turn's model next to this turn's tokens.
        // The live footer has to read like the settled one, so it names the
        // model rather than "ตามค่าของ agent" and carries a clock. A pinned chat
        // knows its model; otherwise the daemon's default is the one that will
        // answer, and it is already in the catalogue.
        val modelLabel = selectedModel.value.ifBlank {
            providers.value.firstOrNull()?.defaultModel.orEmpty()
        }.ifBlank { "ตามค่าของ agent" }
        turnStats.value = TurnStats(
            model = modelLabel,
            startedAtMs = SystemClock.elapsedRealtime(),
            running = true,
        )
        viewModelScope.launch {
            val fresh = repo.send(sessionId, text.trim(), attachments)
            pendingAttachments.value = emptyList()
            if (!fresh) notice.value = "ส่งไปแล้ว กำลังรอการตอบกลับ"
        }
    }

    /** Re-sends the last message after a failure, reusing the same text. */
    fun retryLast() {
        if (lastSentText.isBlank() && lastAttachments.isEmpty()) return
        pendingAttachments.value = lastAttachments
        send(lastSentText)
    }

    /** "แชทใหม่" — the only session action that needs no argument. */
    fun newChat() {
        viewModelScope.launch {
            runCatching { repo.createSession("") }
                .onSuccess {
                    sessionId = it
                    selectedProvider.value = ""
                    selectedModel.value = ""
                    hasStoredRoute.value = false
                    settings.saveSession(it)
                }
                .onFailure { notice.value = "สร้างแชทใหม่ไม่สำเร็จ: ${it.message}" }
        }
    }

    fun openChat(id: String) {
        if (id == sessionId) return
        viewModelScope.launch {
            repo.selectSession(id)
            sessionId = id
            selectedProvider.value = ""
            selectedModel.value = ""
            hasStoredRoute.value = false
            settings.saveSession(id)
            // The header shows this chat's model, not the daemon default.
            runCatching { repo.sessionRoute(id) }.getOrNull()?.let { (provider, model) ->
                selectedProvider.value = provider
                selectedModel.value = model
                hasStoredRoute.value = true
            }
        }
    }

    fun renameChat(id: String, title: String) {
        viewModelScope.launch { repo.renameSession(id, title) }
    }

    fun deleteChat(id: String) {
        viewModelScope.launch {
            repo.deleteSession(id)
            if (id == sessionId) {
                val next = repo.activeSession.value
                sessionId = next
                selectedProvider.value = ""
                selectedModel.value = ""
                hasStoredRoute.value = false
                settings.saveSession(next)
                runCatching { repo.sessionRoute(next) }.getOrNull()?.let { (provider, model) ->
                    selectedProvider.value = provider
                    selectedModel.value = model
                    hasStoredRoute.value = true
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            refreshDaemonStart()
            runCatching { repo.refreshLatest(sessionId) }
                .onFailure { notice.value = "ดึงข้อมูลล่าสุดไม่สำเร็จ: ${it.message}" }
        }
    }

    /**
     * Reads the daemon's start time from its D1 row. Called on open and when the
     * socket comes back; the displayed uptime then advances on its own.
     */
    private suspend fun refreshDaemonStart() {
        val node = repo.node() ?: return
        val started = node.startedAt
        if (started <= 0L) return
        // Same daemon as the last answer: the number on screen is already right,
        // and rewriting it would only cost a re-render and a write to make no
        // change.
        if (started == daemonStartedAt.value) return
        daemonStartedAt.value = started
        settings.saveDaemonStartedAt(started)
    }

    fun loadOlder() {
        viewModelScope.launch {
            runCatching { repo.loadOlder(sessionId) }
                .onFailure { notice.value = "โหลดประวัติเก่าไม่สำเร็จ: ${it.message}" }
        }
    }

    fun clearNotice() { notice.value = "" }

    // ---- Per-session sub-agent settings (ACP session config pattern) ----

    val subAgentProvider = MutableStateFlow("")
    val subAgentModel = MutableStateFlow("")
    val subAgentEnabled = MutableStateFlow(true)
    val subAgentPinned = MutableStateFlow(false)

    fun loadSubAgentConfig() {
        viewModelScope.launch {
            val cfg = repo.sessionAgentConfig(sessionId) ?: return@launch
            subAgentProvider.value = cfg.subProvider
            subAgentModel.value = cfg.subModel
            subAgentEnabled.value = cfg.subEnabled
            subAgentPinned.value = cfg.subPinned
        }
    }

    // Toggling the sub agent on or off must work without picking a route: a
    // blank provider/model tells the daemon "keep the agent's global
    // sub-agent, only override the switch", which is what the daemon writes
    // as sub_enabled with no route change.
    fun setSubAgent(provider: String, model: String, enabled: Boolean) {
        viewModelScope.launch {
            val ok = runCatching {
                repo.saveSessionAgentConfig(sessionId, "", "", false, provider, model, enabled, false)
            }.getOrDefault(false)
            if (ok) {
                subAgentProvider.value = provider
                subAgentModel.value = model
                subAgentEnabled.value = enabled
                subAgentPinned.value = provider.isNotBlank() && model.isNotBlank()
                notice.value = if (subAgentPinned.value) {
                    "Sub agent: $provider / $model"
                } else if (enabled) {
                    "เปิด sub agent ด้วยค่าของ agent"
                } else {
                    "ปิด sub agent ของแชทนี้"
                }
            } else {
                notice.value = "บันทึก sub agent ไม่สำเร็จ"
            }
        }
    }

    fun clearSubAgent() {
        viewModelScope.launch {
            val ok = runCatching {
                repo.saveSessionAgentConfig(sessionId, "", "", false, "", "", null, true)
            }.getOrDefault(false)
            if (ok) {
                subAgentProvider.value = ""
                subAgentModel.value = ""
                subAgentPinned.value = false
                // Re-read the effective values so the toggle shows what the
                // agent's global sub-agent is, not the flag the chat had.
                loadSubAgentConfig()
                notice.value = "Sub agent ใช้ค่าของ agent แล้ว"
            } else {
                notice.value = "ล้างค่า sub agent ไม่สำเร็จ"
            }
        }
    }

    fun chooseSubProvider(id: String) { subAgentProvider.value = id }
    fun chooseSubModel(id: String) { subAgentModel.value = id }
    override fun onCleared() {
        super.onCleared()
    }
}
