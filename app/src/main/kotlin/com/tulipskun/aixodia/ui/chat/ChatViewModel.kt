package com.tulipskun.aixodia.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tulipskun.aixodia.data.remote.ConnState
import com.tulipskun.aixodia.data.repo.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ChatViewModel(private val repo: ChatRepository, val sessionId: String) : ViewModel() {
    val sessions = repo.observeSessions()
    val messages = repo.observeMessages(sessionId)
    val conn: StateFlow<ConnState> = repo.connState as StateFlow<ConnState>
    val status = MutableStateFlow("")

    init {
        viewModelScope.launch { repo.openSession(sessionId) }
        viewModelScope.launch {
            repo.liveFrames.collect { f ->
                if (f.kind == "trace") status.value = f.text.ifEmpty { f.stage }
                else if (f.kind == "done" || f.seq > 0) status.value = ""
            }
        }
    }

    fun send(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch { repo.send(sessionId, text.trim()) }
    }

    fun loadOlder() { viewModelScope.launch { repo.loadOlder(sessionId) } }

    override fun onCleared() { repo.close() }
}
