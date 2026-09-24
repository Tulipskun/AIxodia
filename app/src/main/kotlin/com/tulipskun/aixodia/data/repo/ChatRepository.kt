package com.tulipskun.aixodia.data.repo

import com.tulipskun.aixodia.data.local.AppDatabase
import com.tulipskun.aixodia.data.local.MessageEntity
import com.tulipskun.aixodia.data.local.SessionEntity
import com.tulipskun.aixodia.data.model.AiOutput
import com.tulipskun.aixodia.data.model.ChatMessage
import com.tulipskun.aixodia.data.model.ChatSession
import com.tulipskun.aixodia.data.remote.AiDirectSocket
import com.tulipskun.aixodia.data.remote.ConnState
import com.tulipskun.aixodia.data.remote.HistoryApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Owns the merge of three sources (AXCH-005):
 *  1. Room cache — instant, offline-capable.
 *  2. REST (Cloudflare Worker/D1 in prod, mock DB in tests) — history + the
 *     newest rows, pulled on open, on reconnect and after a cold start.
 *  3. WebSocket — the live tail from the agent, which keeps running server-side
 *     when the app is closed, so every frame is also written to Room and the
 *     next open sees it.
 */
class ChatRepository(
    private val db: AppDatabase,
    private val history: HistoryApi,
    private val socket: AiDirectSocket,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val connState: StateFlow<ConnState> = socket.state
    val socketError: StateFlow<String> = socket.lastError
    val liveFrames get() = socket.frames

    private val _active = MutableStateFlow("default")
    val activeSession: StateFlow<String> = _active

    init {
        scope.launch {
            socket.frames.collect { onFrame(it) }
        }
        scope.launch {
            var wasOnline = false
            socket.state.collect { st ->
                if (st == ConnState.ONLINE) {
                    if (wasOnline) {
                        // reconnect: pull whatever the agent finished while we
                        // were away, then resend anything still queued.
                        refreshLatest(_active.value)
                    }
                    wasOnline = true
                    flushPending()
                } else if (st == ConnState.OFFLINE) {
                    wasOnline = false
                }
            }
        }
    }

    fun observeSessions(): Flow<List<ChatSession>> = db.sessions().observe().map { list ->
        list.map { ChatSession(it.id, it.title, it.model, it.unread, it.lastSnippet, it.lastAt) }
    }

    fun observeMessages(sid: String): Flow<List<ChatMessage>> = db.messages().observe(sid).map { list ->
        list.map {
            ChatMessage(
                id = "${it.sessionId}:${it.seq}",
                sessionId = it.sessionId,
                seq = it.seq,
                role = it.role,
                text = it.text,
                createdAt = it.createdAt,
                pending = it.pending,
                agent = it.agent,
                jobId = it.jobId,
                stage = it.stage,
                toolName = it.toolName,
                toolArgs = it.toolArgs,
                tokensIn = it.tokensIn,
                tokensOut = it.tokensOut,
            )
        }
    }

    /** Session list from the DB (cloud/mock), merged into Room. */
    suspend fun syncSessions() {
        // HistoryApi already degrades to "empty" when the app is not
        // configured; the guard here keeps a transport error from escaping into
        // the ViewModel scope, where it would crash the process.
        val rows = try { history.sessions() } catch (_: Exception) { emptyList() }
        if (rows.isEmpty()) return
        db.sessions().upsertAll(
            rows.map {
                SessionEntity(
                    id = it.id,
                    title = it.title.ifBlank { it.id },
                    model = it.model,
                    lastAt = it.updatedAt * 1000,
                )
            }
        )
    }

    suspend fun openSession(sid: String) {
        _active.value = sid
        if (db.sessions().get(sid) == null) {
            val row = try { history.sessions() } catch (_: Exception) { emptyList() }.firstOrNull { it.id == sid }
            db.sessions().upsert(
                SessionEntity(
                    id = sid,
                    title = row?.title?.ifBlank { sid } ?: sid,
                    model = row?.model ?: "",
                    lastAt = (row?.updatedAt ?: 0) * 1000,
                )
            )
        }
        db.sessions().clearUnread(sid)
        val localMax = db.messages().maxSeq(sid)
        // Older page first (keeps the whole thread), then the newest rows.
        val min = db.messages().minSeq(sid)
        if (db.messages().count(sid) == 0 || min > 1) {
            history.turns(sid, beforeSeq = 0, limit = 200).takeIf { it.isNotEmpty() }?.let { rows ->
                db.messages().insertAll(rows.map { it.toEntity(sid) })
            }
        }
        refreshLatest(sid, skipAtOrBelow = localMax)
        socket.switchSession(sid)
        socket.open(sid, db.messages().maxSeq(sid))
        flushPending()
    }

    suspend fun createSession(title: String): String {
        val id = "s" + System.currentTimeMillis().toString(36) +
            java.util.UUID.randomUUID().toString().take(4)
        db.sessions().upsert(SessionEntity(id = id, title = title.ifBlank { "แชตใหม่" }))
        // Local-first: a cloud failure still leaves a usable local session.
        try { history.createSession(id, title.ifBlank { "แชตใหม่" }) } catch (_: Exception) { }
        openSession(id)
        return id
    }

    /**
     * Chat apps do not ask for a session id: they open the last chat, or start
     * one. This makes that decision here so the UI can stay a plain list.
     */
    suspend fun ensureSession(preferred: String): String {
        val known = try { db.sessions().get(preferred) } catch (_: Exception) { null }
        if (known != null && preferred.isNotBlank()) {
            openSession(preferred)
            return preferred
        }
        // Prefer a session that already has messages, else start a new chat.
        val rows = try { db.sessions().observeAll() } catch (_: Exception) { emptyList() }
        val withContent = rows.firstOrNull { db.messages().count(it.id) > 0 } ?: rows.firstOrNull()
        val id = withContent?.id ?: createSession("")
        openSession(id)
        return id
    }

    suspend fun selectSession(id: String) = openSession(id)

    suspend fun renameSession(id: String, title: String) {
        val clean = title.trim().take(120)
        if (clean.isEmpty()) return
        db.sessions().get(id)?.let { db.sessions().upsert(it.copy(title = clean)) }
        try { history.renameSession(id, clean) } catch (_: Exception) { }
    }

    /** Deletes on both sides; when the active chat goes, the next one opens. */
    suspend fun deleteSession(id: String) {
        try { history.deleteSession(id) } catch (_: Exception) { }
        db.messages().deleteSession(id)
        db.sessions().delete(id)
        if (_active.value == id) {
            val next = try { db.sessions().observeAll().firstOrNull() } catch (_: Exception) { null }
            val nextId = next?.id ?: createSession("")
            _active.value = nextId
            socket.switchSession(nextId)
        }
    }

    /** Newest rows from the DB — the "reopen the app" path. */
    suspend fun refreshLatest(sid: String, skipAtOrBelow: Long = 0) {
        val rows = try { history.latest(sid) } catch (_: Exception) { emptyList() }
        if (rows.isEmpty()) return
        db.messages().insertAll(
            rows.filter { it.seq > skipAtOrBelow }.map { it.toEntity(sid) }
        )
        val last = rows.maxByOrNull { it.seq } ?: return
        val cur = db.sessions().get(sid)
        db.sessions().upsert(
            (cur ?: SessionEntity(id = sid)).copy(
                title = cur?.title?.takeIf { it.isNotBlank() && it != sid } ?: last.text.take(42).ifBlank { sid },
                lastSnippet = last.text.take(120),
                lastAt = if (last.createdAt > 0) last.createdAt * 1000 else System.currentTimeMillis(),
            )
        )
    }

    /**
     * Sends a message. Offline (or before the socket is ready) it is stored
     * with pending=true and flushed on the next successful connection.
     */
    suspend fun send(sid: String, text: String) {
        val clientMsgId = "$sid:${System.currentTimeMillis()}"
        val seq = db.messages().maxSeq(sid) + 1
        db.messages().upsert(
            MessageEntity(
                sessionId = sid,
                seq = seq,
                role = "user",
                text = text,
                createdAt = System.currentTimeMillis(),
                pending = true,
                clientMsgId = clientMsgId,
            )
        )
        val queued = socket.send(sid, text, clientMsgId)
        if (queued) {
            db.messages().markAcked(clientMsgId)
        }
        // queued == false → stays pending, the state collector retries it.
    }

    suspend fun flushPending() {
        val pending = db.messages().allPending()
        for (m in pending) {
            if (m.clientMsgId.isBlank()) continue
            if (socket.send(m.sessionId, m.text, m.clientMsgId)) {
                db.messages().markAcked(m.clientMsgId)
            }
        }
    }

    suspend fun loadOlder(sid: String) {
        val min = db.messages().minSeq(sid)
        if (min <= 1) return
        (try { history.turns(sid, beforeSeq = min, limit = 100) } catch (_: Exception) { emptyList() })
            .takeIf { it.isNotEmpty() }?.let { rows ->
            db.messages().insertAll(rows.map { it.toEntity(sid) })
        }
    }

    private suspend fun onFrame(f: AiOutput) {
        when (f.kind) {
            "ack" -> {
                if (f.clientMsgId.isNotBlank()) db.messages().markAcked(f.clientMsgId)
                return
            }
            "error" -> {
                if (f.text.isNotBlank() && f.sessionId.isNotBlank()) {
                    record(f, f.sessionId)
                }
                return
            }
            "done" -> return
        }
        val sid = f.sessionId.ifBlank { _active.value }
        record(f, sid)
    }

    private suspend fun record(f: AiOutput, sid: String) {
        val text = f.text.ifEmpty { f.content.joinToString("") { it.text } }
        val hasBody = text.isNotBlank() || f.toolCall != null
        if (!hasBody) return
        val seq = if (f.seq > 0) f.seq else db.messages().maxSeq(sid) + 1
        db.messages().upsert(
            MessageEntity(
                sessionId = sid,
                seq = seq,
                role = f.role.ifBlank { "model" },
                text = text,
                createdAt = System.currentTimeMillis(),
                clientMsgId = f.clientMsgId,
                agent = f.agent,
                jobId = f.jobId,
                stage = f.stage,
                toolName = f.toolCall?.name ?: "",
                toolArgs = f.toolCall?.arguments ?: "",
                tokensIn = f.inputTokens,
                tokensOut = f.outputTokens,
            )
        )
        val cur = db.sessions().get(sid)
        db.sessions().upsert(
            (cur ?: SessionEntity(id = sid, title = sid)).copy(
                lastSnippet = text.take(120),
                lastAt = System.currentTimeMillis(),
            )
        )
        if (sid != _active.value) db.sessions().bumpUnread(sid)
    }

    private fun com.tulipskun.aixodia.data.remote.TurnRow.toEntity(sid: String) = MessageEntity(
        sessionId = sid,
        seq = seq,
        role = role.ifBlank { "model" },
        text = text,
        createdAt = if (createdAt > 0) createdAt * 1000 else System.currentTimeMillis(),
        agent = agent,
        jobId = jobId,
    )

    fun close() = socket.close()
}
