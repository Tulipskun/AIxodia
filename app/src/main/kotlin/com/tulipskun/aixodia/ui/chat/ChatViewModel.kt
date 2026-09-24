package com.tulipskun.aixodia.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tulipskun.aixodia.SettingsStore
import com.tulipskun.aixodia.data.remote.ConnState
import com.tulipskun.aixodia.data.repo.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ChatViewModel(
    private val repo: ChatRepository,
    private val settings: SettingsStore,
    val sessionId: String,
) : ViewModel() {

    val messages = repo.observeMessages(sessionId)
    val sessions = repo.observeSessions()
    val conn: StateFlow<ConnState> = repo.connState

    /** Why the socket is down, straight from the daemon's HTTP answer. */
    val socketError = repo.socketError

    /** Latest live status line for the thread ("sub: web_fetch …"). */
    val status = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val notice = MutableStateFlow("")

    init {
        // A fresh install has no settings yet, and every call here is network
        // facing: failures belong in [notice], never as an uncaught exception
        // (an uncaught one in viewModelScope takes the whole process down).
        viewModelScope.launch {
            runCatching {
                repo.syncSessions()
                repo.openSession(sessionId)
            }.onFailure { err ->
                notice.value = "เชื่อมต่อไม่ได้: ${err.message ?: err::class.simpleName}"
            }
        }
        viewModelScope.launch {
            repo.liveFrames.collect { f ->
                if (f.sessionId.isNotEmpty() && f.sessionId != sessionId) return@collect
                when (f.kind) {
                    "trace" -> status.value = f.text.ifEmpty { f.stage }
                    "message" -> if (f.agent.isNotEmpty() && f.agent != "main") status.value = f.text.take(80)
                    "done" -> { status.value = ""; busy.value = false }
                    "error" -> { status.value = ""; busy.value = false; notice.value = f.text }
                    "ack" -> { busy.value = false; notice.value = "" }
                }
            }
        }
    }

    fun send(text: String) {
        if (text.isBlank()) return
        busy.value = true
        viewModelScope.launch { repo.send(sessionId, text.trim()) }
    }

    fun newSession() {
        viewModelScope.launch {
            runCatching { repo.createSession("") }
                .onSuccess { settings.saveSession(it) }
                .onFailure { notice.value = "สร้างเซสชันไม่สำเร็จ: ${it.message}" }
        }
    }

    fun switchTo(id: String) {
        if (id == sessionId) return
        viewModelScope.launch { settings.saveSession(id) }
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
