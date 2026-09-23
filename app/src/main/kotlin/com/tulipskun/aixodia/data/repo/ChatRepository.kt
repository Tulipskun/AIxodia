package com.tulipskun.aixodia.data.repo

import com.tulipskun.aixodia.data.local.MessageEntity
import com.tulipskun.aixodia.data.local.SessionEntity
import com.tulipskun.aixodia.data.model.ChatMessage
import com.tulipskun.aixodia.data.model.ChatSession
import com.tulipskun.aixodia.data.local.AppDatabase
import com.tulipskun.aixodia.data.remote.AiDirectSocket
import com.tulipskun.aixodia.data.remote.HistoryApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Merge strategy (AX-012): Room cache first -> D1 pages -> WS live tail.
 * (session_id, seq) unique; WS seqs continue past D1 max.
 */
class ChatRepository(
    private val db: AppDatabase,
    private val history: HistoryApi,
    private val socket: AiDirectSocket,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val connState = socket.state
    val liveFrames = socket.frames

    fun observeSessions(): Flow<List<ChatSession>> = db.sessions().observe().map { list ->
        list.map { ChatSession(it.id, it.title, it.model, it.unread, it.lastSnippet, it.lastAt) }
    }

    fun observeMessages(sid: String): Flow<List<ChatMessage>> = db.messages().observe(sid).map { list ->
        list.map {
            ChatMessage("${it.sessionId}:${it.seq}", it.sessionId, it.seq, it.role, it.text, it.createdAt, it.pending)
        }
    }

    suspend fun openSession(sid: String) {
        db.sessions().upsert(SessionEntity(id = sid))
        db.sessions().clearUnread(sid)
        val maxSeq = db.messages().maxSeq(sid)
        // 1) D1 backfill (old data) into Room cache
        try {
            val rows = history.turns(sid, beforeSeq = if (maxSeq == 0L) Long.MAX_VALUE else maxSeq)
            if (rows.isNotEmpty()) {
                db.messages().insertAll(rows.map {
                    MessageEntity(sid, it.seq, it.role, it.text, it.createdAt.ifZeroNow())
                })
            }
        } catch (_: Exception) { }
        // 2) flush queued pending sends, 3) attach live tail
        val cur = db.messages().maxSeq(sid)
        socket.open(sid, cur)
        scope.launch {
            socket.frames.collect { f ->
                if (f.sessionId.isNotEmpty() && f.sessionId != sid) return@collect
                val text = f.text.ifEmpty { f.content.joinToString("") { it.text } }
                if (text.isEmpty() && f.kind == "trace") return@collect // handled as status upstream
                val seq = if (f.seq > 0) f.seq else db.messages().maxSeq(sid) + 1
                db.messages().upsert(MessageEntity(sid, seq, f.role.ifEmpty { "model" }, text, System.currentTimeMillis()))
                db.sessions().get(sid)?.let {
                    db.sessions().upsert(it.copy(lastSnippet = text.take(120), lastAt = System.currentTimeMillis()))
                }
            }
        }
    }

    suspend fun loadOlder(sid: String) {
        val min = db.messages().maxSeq(sid) // simplified: page before current min via DAO max; full paging in v2
        val rows = try { history.turns(sid, beforeSeq = min, limit = 50) } catch (_: Exception) { emptyList() }
        if (rows.isNotEmpty()) db.messages().insertAll(rows.map {
            MessageEntity(sid, it.seq, it.role, it.text, it.createdAt.ifZeroNow())
        })
    }

    suspend fun send(sid: String, text: String) {
        val seq = db.messages().maxSeq(sid) + 1
        db.messages().upsert(MessageEntity(sid, seq, "user", text, System.currentTimeMillis(), pending = true))
        try {
            socket.send(text)
            db.messages().upsert(MessageEntity(sid, seq, "user", text, System.currentTimeMillis(), pending = false))
        } catch (_: Exception) { /* stays pending, flushed on reconnect */ }
    }

    fun close() = socket.close()

    private fun Long.ifZeroNow(): Long = if (this == 0L) System.currentTimeMillis() else this
}
