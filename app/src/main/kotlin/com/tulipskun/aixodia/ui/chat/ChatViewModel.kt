package com.tulipskun.aixodia.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.os.SystemClock
import com.tulipskun.aixodia.SettingsStore
import com.tulipskun.aixodia.data.model.AiOutput
import com.tulipskun.aixodia.data.model.ProviderView
import com.tulipskun.aixodia.data.model.ToolStep
import com.tulipskun.aixodia.data.remote.ConnState
import com.tulipskun.aixodia.data.repo.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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
    val exact: Boolean = false,
    val startedAtMs: Long = 0L,
    val endedAtMs: Long = 0L,
    val estimatedTokens: Int = 0,
    val running: Boolean = false,
) {
    fun elapsedMs(nowMs: Long): Long {
        val end = if (endedAtMs > 0L) endedAtMs else nowMs
        return (end - startedAtMs).coerceAtLeast(0L)
    }

    fun outputTokensNow(): Int = if (exact) outputTokens else estimatedTokens

    fun tokensPerSecond(nowMs: Long): Double {
        val millis = elapsedMs(nowMs)
        if (millis <= 0L) return 0.0
        return outputTokensNow().toDouble() * 1000.0 / millis.toDouble()
    }
}

class ChatViewModel(
    private val repo: ChatRepository,
    private val settings: SettingsStore,
    initialSession: String,
) : ViewModel() {
    /** The chat currently on screen; set by ensureSession, like a chat app. */
    var sessionId: String = initialSession
        private set

    val messages = repo.observeMessages(sessionId)
    val sessions = repo.observeSessions()
    val conn: StateFlow<ConnState> = repo.connState

    /** Why the socket is down, straight from the daemon's HTTP answer. */
    val socketError = repo.socketError

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
    val liveSteps = MutableStateFlow<List<ToolStep>>(emptyList())
    val providers = MutableStateFlow<List<ProviderView>>(emptyList())

    /** The sub agents this turn started, newest last, each with its own stop. */
    val subAgents = MutableStateFlow<List<SubAgentActivity>>(emptyList())

    /** Model, tokens, elapsed time and rate for the footer. */
    val turnStats = MutableStateFlow(TurnStats())
    private var liveSubText = ""
    private var liveChars = 0
    val selectedProvider = MutableStateFlow("")
    val selectedModel = MutableStateFlow("")

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
            }
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
                        liveSteps.value = emptyList()
                        busy.value = false
                        notice.value = f.text
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
        if (chunk.isEmpty()) return
        if (agent.isNotEmpty() && agent != "main") {
            status.value = "${if (agent == "sub") "sub" else agent}: " + (liveSubText + chunk).take(80)
            liveSubText += chunk
            noteSubAgent(frame, chunk.take(80))
            return
        }
        liveAgent.value = agent.ifEmpty { "main" }
        liveText.value += chunk
        liveChars += chunk.length
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
        liveText.value = text
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
        if (input <= 0 && output <= 0) return
        turnStats.value = turnStats.value.copy(
            inputTokens = input,
            outputTokens = output,
            exact = true,
            estimatedTokens = output,
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
                liveSteps.value = liveSteps.value.filterNot { it.name == name } +
                    ToolStep(name = name, args = f.toolCall?.arguments.orEmpty())
                status.value = "$name…"
            }
            "tool_result" -> {
                val name = f.toolResult?.name ?: f.toolCall?.name ?: return
                val result = f.toolResult?.text.orEmpty()
                liveSteps.value = liveSteps.value.filterNot { it.name == name } +
                    ToolStep(
                        name = name,
                        args = f.toolCall?.arguments.orEmpty(),
                        result = result.take(160),
                        isError = f.toolResult?.isError ?: false,
                        done = true,
                    )
                status.value = if (f.toolResult?.isError == true) "$name ล้มเหลว" else "$name เสร็จแล้ว"
            }
            "response_text" -> status.value = "กำลังคิด…"
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
        val streamed = liveText.value
        liveText.value = ""
        liveSubText = ""
        liveSteps.value = emptyList()
        busy.value = false
        subAgents.value = subAgents.value.map { it.copy(running = false) }
        finishStats()
        // The daemon mirrored the turn into D1; pull it so the bubble is
        // replaced by the stored row instead of a second copy.
        if (streamed.isNotBlank()) refresh()
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
            // Start from what this chat already uses: the picker must not look
            // like a pending change just because it was opened.
            if (selectedProvider.value.isBlank()) {
                runCatching { repo.sessionRoute(sessionId) }.getOrNull()?.let { (provider, model) ->
                    val view = list.firstOrNull { it.id == provider }
                    selectedProvider.value = provider
                    selectedModel.value = model.takeIf { m -> view?.models?.any { it.id == m } == true }
                        ?: view?.defaultModel.orEmpty()
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
            if (ok) notice.value = "ใช้ $provider / $model กับแชทนี้แล้ว" else notice.value = "บันทึก provider/model ไม่สำเร็จ"
        }
    }

    fun send(text: String) {
        if (text.isBlank()) return
        busy.value = true
        liveChars = 0
        liveSubText = ""
        subAgents.value = emptyList()
        // The footer opens with this turn: which model answers, from when, and
        // nothing else yet. A chat that follows the agent's default says so —
        // printing whatever route happens to be current later would put another
        // turn's model next to this turn's tokens.
        val route = listOf(selectedProvider.value, selectedModel.value)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .ifBlank { "ตามค่าของ agent" }
        turnStats.value = TurnStats(
            model = route,
            startedAtMs = SystemClock.elapsedRealtime(),
            running = true,
        )
        viewModelScope.launch { repo.send(sessionId, text.trim()) }
    }

    /** "แชทใหม่" — the only session action that needs no argument. */
    fun newChat() {
        viewModelScope.launch {
            runCatching { repo.createSession("") }
                .onSuccess {
                    sessionId = it
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
            settings.saveSession(id)
            // The header shows this chat's model, not the daemon default.
            runCatching { repo.sessionRoute(id) }.getOrNull()?.let { (provider, model) ->
                selectedProvider.value = provider
                selectedModel.value = model
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
                settings.saveSession(next)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { repo.refreshLatest(sessionId) }
                .onFailure { notice.value = "ดึงข้อมูลล่าสุดไม่สำเร็จ: ${it.message}" }
        }
    }

    fun loadOlder() {
        viewModelScope.launch {
            runCatching { repo.loadOlder(sessionId) }
                .onFailure { notice.value = "โหลดประวัติเก่าไม่สำเร็จ: ${it.message}" }
        }
    }

    fun clearNotice() { notice.value = "" }

    override fun onCleared() {
        // The socket is app-wide; leaving it open keeps the agent's live tail
        // flowing for the next screen. The daemon keeps working either way.
        super.onCleared()
    }
}
