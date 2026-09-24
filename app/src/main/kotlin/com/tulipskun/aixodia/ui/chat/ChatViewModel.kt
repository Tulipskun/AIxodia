package com.tulipskun.aixodia.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tulipskun.aixodia.SettingsStore
import com.tulipskun.aixodia.data.model.ProviderView
import com.tulipskun.aixodia.data.model.ToolStep
import com.tulipskun.aixodia.data.remote.ConnState
import com.tulipskun.aixodia.data.repo.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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
    private var liveSubText = ""
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
        }
        viewModelScope.launch {
            repo.liveFrames.collect { f ->
                if (f.sessionId.isNotEmpty() && f.sessionId != sessionId) return@collect
                when (f.kind) {
                    // One streamed chunk: append to the bubble the phone is
                    // already showing, or to the sub agent's status line.
                    "delta" -> onDelta(f.agent, f.text)
                    // The authoritative answer. A streamed turn already filled
                    // the bubble, so this only confirms it.
                    "message" -> onMessage(f.agent, f.text)
                    "trace" -> onTrace(f)
                    "done" -> onTurnDone(f.stage)
                    "error" -> {
                        status.value = ""
                        liveText.value = ""
                        liveSteps.value = emptyList()
                        busy.value = false
                        notice.value = f.text
                    }
                    // The ack only means the message reached the daemon. The
                    // turn keeps running until `done`, and until then the stop
                    // button must stay on screen.
                    "ack" -> { notice.value = "" }
                }
            }
        }
    }

    private fun onDelta(agent: String, chunk: String) {
        if (chunk.isEmpty()) return
        if (agent.isNotEmpty() && agent != "main") {
            status.value = "${if (agent == "sub") "sub" else agent}: " + (liveSubText + chunk).take(80)
            liveSubText += chunk
            return
        }
        liveAgent.value = agent.ifEmpty { "main" }
        liveText.value += chunk
        busy.value = true
    }

    private fun onMessage(agent: String, text: String) {
        if (text.isBlank()) return
        if (agent.isNotEmpty() && agent != "main") {
            status.value = "${if (agent == "sub") "sub" else agent}: " + text.take(80)
            liveSubText = text
            return
        }
        // Replace whatever streamed in with the daemon's own copy: it is the one
        // that also lands in D1.
        liveText.value = text
        busy.value = true
    }

    private fun onTrace(f: com.tulipskun.aixodia.data.model.AiOutput) {
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

    private fun onTurnDone(stage: String) {
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
        // The daemon mirrored the turn into D1; pull it so the bubble is
        // replaced by the stored row instead of a second copy.
        if (streamed.isNotBlank()) refresh()
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
            if (selectedProvider.value.isBlank()) {
                list.firstOrNull()?.let { pick(it) }
            }
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
            settings.saveSession(id)
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
