package com.tulipskun.aixodia.data.repo

import androidx.room.withTransaction
import com.tulipskun.aixodia.data.local.AppDatabase
import com.tulipskun.aixodia.data.local.MessageEntity
import com.tulipskun.aixodia.data.local.SessionEntity
import com.tulipskun.aixodia.data.model.AiOutput
import com.tulipskun.aixodia.data.model.ChatMessage
import com.tulipskun.aixodia.data.model.ProviderView
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
                    // The first ONLINE also repairs the chat list: settings can
                    // be written a moment after the screen is created, so an
                    // earlier sync would have seen an empty endpoint and pulled
                    // nothing.
                    syncSessions()
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
                cacheRead = it.cacheRead,
                cacheWrite = it.cacheWrite,
                model = it.model,
                durationMs = it.durationMs,
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
                reconcile(sid, rows)
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
    /** Stops the turn the daemon is running for this chat. */
    fun stopTurn(sid: String): Boolean = socket.cancel(sid)

    /** Stops one sub agent of this chat and leaves the turn running. */
    fun stopSubAgent(sid: String, jobId: String): Boolean = socket.cancelSubAgent(sid, jobId)

    /** The provider and model the daemon currently offers, for the picker. */
    suspend     fun models(): List<ProviderView> = history.models()

    /** Global provider health/key counts for the session picker caption. */
    suspend fun providerStatuses(): List<com.tulipskun.aixodia.data.model.ProviderStatus> = history.providers()

    /** The provider/model the daemon has stored for one chat, if it has one. */
    suspend fun sessionRoute(sid: String): Pair<String, String>? =
        runCatching { history.sessions().firstOrNull { it.id == sid } }
            .getOrNull()
            ?.let { it.provider to it.model }
            ?.takeIf { it.first.isNotBlank() }

    /**
     * Pins provider and model for one chat. The daemon validates the pair and
     * stores it on the session, so the choice survives a restart and is shared
     * with any other phone.
     */
    suspend fun setSessionModel(sid: String, provider: String, model: String): Boolean =
        history.setSessionModel(sid, provider, model)

    /** Removes one chat's stored route; the daemon then uses the agent defaults. */
    suspend fun clearSessionModel(sid: String): Boolean = history.clearSessionModel(sid)

    suspend fun refreshLatest(sid: String, skipAtOrBelow: Long = 0) {
        val rows = try { history.latest(sid) } catch (_: Exception) { emptyList() }
        if (rows.isEmpty()) return
        // The whole page goes to reconcile, not just the rows past the local
        // max: a row the old filter hid is healed instead of lost.
        reconcile(sid, rows)
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
            reconcile(sid, rows)
        }
    }

    /**
     * Merges one pulled page into the local table. The phone numbers its
     * pending row from its own counter while D1 numbers every row MAX+1, so the
     * two counters can disagree — after a slow user mirror, a dropped write, or
     * an unlucky restart. Blindly appending past the local max then hides rows
     * forever behind a seq collision the next pull can never repair, so every
     * row is decided on its own:
     *
     * - no local row at this seq: insert (new row, or a historical gap);
     * - same role and text: adopt it (clears a stale pending flag);
     * - different content: the counters diverged. D1 wins, but only when the
     *   displaced local row's text is also in this page (its true twin): that
     *   way a message is never deleted without its replacement on screen.
     *   Otherwise the local row stays — the mirror is still in flight, or the
     *   twin is outside this page, and dropping either side would lose text.
     */
    private suspend fun reconcile(sid: String, rows: List<com.tulipskun.aixodia.data.remote.TurnRow>) {
        if (rows.isEmpty()) return
        // TEMP-DEBUG-AXGH: reconcile diagnostics, reverted before release.
        android.util.Log.d("AIXDBG", "reconcile sid=$sid pulled=${rows.map { "${it.seq}:${it.role}:${it.text.take(18)}" }}")
        val entities = rows.map { it.toEntity(sid) }
        db.withTransaction {
            for (e in entities.sortedBy { it.seq }) {
                val existing = db.messages().get(sid, e.seq)
                if (existing == null) {
                    android.util.Log.d("AIXDBG", "insert seq=${e.seq} role=${e.role} text=${e.text.take(24)}")
                    db.messages().upsert(e)
                    continue
                }
                if (existing.role == e.role && existing.text == e.text) {
                    android.util.Log.d("AIXDBG", "adopt seq=${e.seq}")
                    if (existing.pending || existing.createdAt != e.createdAt) {
                        db.messages().upsert(e.copy(pending = false, clientMsgId = existing.clientMsgId))
                    }
                    continue
                }
                val twinInPage = entities.any { it.seq != e.seq && it.text == existing.text }
                android.util.Log.d("AIXDBG", "collision seq=${e.seq} local=(${existing.role},${existing.text.take(24)},pending=${existing.pending}) remote=(${e.role},${e.text.take(24)}) twin=$twinInPage")
                if (twinInPage) {
                    db.messages().deleteOne(sid, e.seq)
                    db.messages().upsert(e)
                }
            }
        }
    }

    /**
     * The local table is a mirror of D1, so a live frame must never become a row
     * here: a streamed answer arrives as dozens of delta frames and would show
     * up as dozens of bubbles, and the daemon's own mirror would then duplicate
     * every one of them. Only the ack marker matters locally; the thread is
     * rebuilt from D1 when the turn closes (AX-082).
     */
    private suspend fun onFrame(f: AiOutput) {
        if (f.kind == "ack" && f.clientMsgId.isNotBlank()) {
            db.messages().markAcked(f.clientMsgId)
        }
    }


    private fun com.tulipskun.aixodia.data.remote.TurnRow.toEntity(sid: String) = MessageEntity(
        sessionId = sid,
        seq = seq,
        role = role.ifBlank { "model" },
        text = text,
        createdAt = if (createdAt > 0) createdAt * 1000 else System.currentTimeMillis(),
        agent = agent,
        jobId = jobId,
        // The footer travels with the turn, so a chat reopened tomorrow still
        // shows which model answered and what it cost (AX-095).
        tokensIn = inputTokens,
        tokensOut = outputTokens,
        cacheRead = cacheRead,
        cacheWrite = cacheWrite,
        model = model,
        durationMs = durationMs,
    )

    fun close() = socket.close()
}
