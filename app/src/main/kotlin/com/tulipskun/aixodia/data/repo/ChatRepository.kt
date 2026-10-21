package com.tulipskun.aixodia.data.repo

import com.tulipskun.aixodia.data.model.mergeToolPairs
import com.tulipskun.aixodia.data.model.normalizedRole
import com.tulipskun.aixodia.data.model.toolRowView
import androidx.room.withTransaction
import com.tulipskun.aixodia.data.local.AppDatabase
import com.tulipskun.aixodia.data.local.MessageEntity
import com.tulipskun.aixodia.data.local.SessionEntity
import com.tulipskun.aixodia.data.model.AiOutput
import com.tulipskun.aixodia.data.model.Block
import com.tulipskun.aixodia.data.model.ChatMessage
import com.tulipskun.aixodia.data.model.ProviderView
import com.tulipskun.aixodia.data.model.ChatSession
import com.tulipskun.aixodia.data.remote.AiDirectSocket
import com.tulipskun.aixodia.data.remote.ConnState
import com.tulipskun.aixodia.data.remote.HistoryApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Owns the merge of three sources:
 *  1. Room cache — instant, offline-capable.
 *  2. Cloudflare D1 over its REST API — history + the newest rows,
 *     pulled on open, on reconnect and after a cold start. The phone reads D1
 *     itself, so this still answers with the daemon stopped.
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
    /** A string resource id (0 = nothing wrong). */
    val socketError: StateFlow<Int> = socket.lastError
    // UI sees the closing frame only after its authoritative rows reach Room.
    private val processedFrames = MutableSharedFlow<AiOutput>(extraBufferCapacity = 64)
    val liveFrames = processedFrames.asSharedFlow()

    private val _active = MutableStateFlow("default")
    val activeSession: StateFlow<String> = _active

    init {
        scope.launch {
            socket.frames.collect {
                val frame = try {
                    onFrame(it)
                    it
                } catch (err: CancellationException) {
                    throw err
                } catch (_: Exception) {
                    // Keep consuming frames and allow the recovery read at close.
                    it.copy(turnsSynced = false)
                }
                processedFrames.emit(frame)
            }
        }
        scope.launch {
            var connectedBefore = false
            var wasOnline = false
            socket.state.collect { st ->
                if (st == ConnState.ONLINE) {
                    if (connectedBefore && !wasOnline) {
                        // reconnect: pull whatever the agent finished while we
                        // were away (incremental, no full re-pull), verify
                        // against D1 and repair only missing turns, then resend
                        // anything still queued.
                        refreshLatest(_active.value)
                        scope.launch { verify(_active.value) }
                    }
                    // The first ONLINE also repairs the chat list: settings can
                    // be written a moment after the screen is created, so an
                    // earlier sync would have seen an empty endpoint and pulled
                    // nothing.
                    syncSessions()
                    wasOnline = true
                    connectedBefore = true
                    flushPending()
                } else {
                    wasOnline = false
                }
            }
        }
    }

    fun observeSessions(): Flow<List<ChatSession>> = db.sessions().observe().map { list ->
        list.map { ChatSession(it.id, it.title, it.model, it.unread, it.lastSnippet, it.lastAt) }
    }

    fun observeMessages(sid: String): Flow<List<ChatMessage>> = db.messages().observe(sid).map { list ->
        // An answer with no text and no tool step is an empty bubble, not a reply.
        // Tool rows and pending messages always stay visible.
        list.filterNot { isEmptyAnswer(it.role, it.text, it.toolName, it.pending) }.map {
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
                reasoningTokens = it.reasoningTokens,
                inputIncludesCache = it.inputIncludesCache,
                model = it.model,
                durationMs = it.durationMs,
                generationMs = it.generationMs,
                thinkingMs = it.thinkingMs,
                reasoningContent = it.reasoningContent,
                block = it.block,
            )
        }.let(::mergeToolPairs)
    }

    /**
     * Session list from D1, merged into Room. D1 is authoritative when it
     * answers: local chats missing server-side are pruned (with their
     * messages), so a server-side wipe also clears the app. A failed fetch
     * (null) keeps everything; sessions with unsent pending messages are
     * never pruned.
     */
    suspend fun syncSessions() {
        val rows = try { history.sessionsOrNull() } catch (_: Exception) { null } ?: return
        val ids = rows.map { it.id }.toSet()
        val local = try { db.sessions().observeAll() } catch (_: Exception) { emptyList() }
        val pending = try { db.messages().allPending().map { it.sessionId }.toSet() } catch (_: Exception) { emptySet() }
        // The daemon sends its newest page, not every chat. Treating an absent
        // id as deleted once the page is full would erase older chats from the
        // phone every time the user has more than one page of history.
        val complete = rows.size < SESSION_LIST_PAGE
        for (session in local) {
            if (session.id in ids) continue
            // A New chat that has never sent its first message is deliberately
            // absent from D1. Keeping it is what makes "New chat" instant.
            if (!session.remote) continue
            if (!complete) continue
            if (session.id in pending) continue
            try {
                db.messages().deleteSession(session.id)
                db.sessions().delete(session.id)
            } catch (_: Exception) { }
        }
        if (rows.isEmpty()) return
        // Update every server chat, not just insert the missing ones. The old
        // IGNORE upsert left titles, models and timestamps frozen at the moment
        // a chat first appeared on the phone, so the drawer kept an old order.
        val merged = mergeSessionRows(local, rows, pending)
        db.withTransaction {
            for (session in merged) db.sessions().upsert(session)
        }
    }

    /**
     * The daemon answered that this session does not exist (it stores the
     * history, so a session can be gone while the phone still lists it). Keep
     * the transcript on screen but stop retrying it: a pending message is
     * flushed on every reconnect and can never land, and a session with a
     * pending message is never pruned by [syncSessions] either, so the chat
     * would stay stuck forever.
     */
    suspend fun forgetDeadSession(sid: String) {
        if (sid.isEmpty()) return
        try { db.messages().clearPending(sid) } catch (_: Exception) { }
    }

    suspend fun openSession(sid: String) {
        _active.value = sid
        val local = try { db.sessions().get(sid) } catch (_: Exception) { null }
        if (local == null) {
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
        // A draft has no D1 row to read or verify; asking would only log a 404.
        if (local?.remote != false) {
            // One newest-page read on open; loadOlder backfills the rest on demand.
            refreshLatest(sid)
            scope.launch { verify(sid) }
        }
        socket.switchSession(sid)
        socket.open(sid, db.messages().maxSeq(sid))
        flushPending()
    }

    /**
     * The screen's New chat: a phone-only row with a client id. Nothing goes to
     * the daemon until [ensureRemoteSession] promotes it on the first send.
     */
    suspend fun createDraftSession(title: String = ""): String {
        val id = "s" + System.currentTimeMillis().toString(36) +
            java.util.UUID.randomUUID().toString().replace("-", "").take(8)
        db.sessions().upsert(
            SessionEntity(id = id, title = title.ifBlank { "New chat" }, remote = false)
        )
        openSession(id)
        return id
    }

    /** True while [sid] is a New chat the daemon has not been told about yet. */
    suspend fun isDraft(sid: String): Boolean =
        try { db.sessions().get(sid)?.remote == false } catch (_: Exception) { false }

    /**
     * Creates the server row for a local draft, keeping its id. Idempotent on
     * the engine, so a retried promote cannot make a second chat. Returns false
     * when the daemon is unreachable or an older daemon ignored the id.
     */
    suspend fun ensureRemoteSession(sid: String, title: String): Boolean {
        if (sid.isBlank()) return false
        val local = try { db.sessions().get(sid) } catch (_: Exception) { null }
        if (local != null && local.remote) return true
        val engineId = try {
            history.createSession(sid, title.ifBlank { "New chat" })
        } catch (_: Exception) {
            ""
        }
        if (engineId != sid) return false
        db.sessions().upsert(
            (local ?: SessionEntity(id = sid, title = title.ifBlank { "New chat" }))
                .copy(remote = true)
        )
        return true
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
        // A fresh chat is local-only, so opening the app never creates a daemon
        // session before the person has actually said something.
        val rows = try { db.sessions().observeAll() } catch (_: Exception) { emptyList() }
        val withContent = rows.firstOrNull { db.messages().count(it.id) > 0 } ?: rows.firstOrNull()
        val id = withContent?.id ?: return createDraftSession()
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
            val nextId = next?.id ?: createDraftSession()
            _active.value = nextId
            socket.switchSession(nextId)
        }
    }

    /** Newest rows from the DB — the "reopen the app" path. */
    /** Stops the turn the daemon is running for this chat. */
    fun stopTurn(sid: String): Boolean = socket.cancel(sid)

    /**
     * Resumes the last question the engine stored but never answered. This
     * sends no new content: the phone keeps the single user bubble it already
     * has, and the provider sees the existing tool trail instead of the same
     * question appended twice.
     */
    fun retryTurn(sid: String): Boolean =
        if (sid.isBlank()) false else socket.retry(sid, "$sid:retry:${System.currentTimeMillis()}")

    /** The provider and model the daemon currently offers, for the picker. */
    suspend     fun models(): List<ProviderView> = history.models()

    /**
     * The daemon's D1 row: tunnel URL, heartbeat age and the unix second it
     * started. The header reads the start time from here once and counts
     * uptime locally, so no periodic healthz call is made.
     */
    suspend fun node(): com.tulipskun.aixodia.data.remote.NodeInfo? =
        runCatching { history.node() }.getOrNull()

    /** Uploads one attachment; null when the daemon could not take it. */
    suspend fun uploadAttachment(
        sid: String,
        name: String,
        mime: String,
        open: () -> java.io.InputStream?,
    ): com.tulipskun.aixodia.data.model.AttachmentRef? =
        history.uploadAttachment(sid, name, mime, open)

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

    suspend fun refreshLatest(sid: String) {
        // Incremental: only pull turns newer than what Room already has, so a
        // reconnect does not re-download the whole history (Telegram/Discord style).
        val localMax = try { db.messages().maxSeq(sid) } catch (_: Exception) { 0L }
        val rows = if (localMax <= 0L) {
            // A cold open has no local seq to continue from. Pull the newest
            // page, not the oldest one: the optimistic row for the next send is
            // numbered from here, and an old page made it collide with turns
            // that were already on the server.
            try { history.latest(sid, limit = 10_000) } catch (_: Exception) { emptyList() }
        } else {
            try { history.turnsAfter(sid, localMax) } catch (_: Exception) { emptyList() }
        }
        if (rows.isEmpty()) return
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

    /** Verifies Room vs D1 and repairs only missing turns (no full re-pull). */
    suspend fun verify(sid: String): Int {
        val localMax = try { db.messages().maxSeq(sid) } catch (_: Exception) { 0L }
        val remote = try { history.turnsAfter(sid, localMax) } catch (_: Exception) { emptyList() }
        if (remote.isEmpty()) return 0
        reconcile(sid, remote)
        return remote.size
    }

    /** The highest stored seq, so a turn can stamp only the rows it created. */
    suspend fun maxSeq(sid: String): Long = try { db.messages().maxSeq(sid) } catch (_: Exception) { 0L }

    /**
     * True when the last committed question has no answer after it. A pending
     * row is not included: it has not reached the daemon yet, and flushPending
     * owns it. This is the "the app was closed or the socket died mid-turn"
     * test the recovery pass uses before retrying.
     */
    suspend fun hasUnfinishedTurn(sid: String): Boolean {
        if (sid.isBlank()) return false
        val rows = try { db.messages().allNow(sid) } catch (_: Exception) { return false }
        val lastUser = rows.indexOfLast { it.role == "user" }
        if (lastUser < 0 || rows[lastUser].pending) return false
        return rows.drop(lastUser + 1).none { it.isStoredAnswer() }
    }

    /** When the last committed question was written, or 0 when there is none. */
    suspend fun lastUserCreatedAt(sid: String): Long {
        if (sid.isBlank()) return 0L
        return try {
            db.messages().allNow(sid).lastOrNull { it.role == "user" }?.createdAt ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun MessageEntity.isStoredAnswer(): Boolean {
        if (role != "model" && role != "assistant") return false
        // The engine's final answer carries usage or the answer block id.
        // Narration before a tool round carries neither, so a turn that died
        // after narrating is still seen as unfinished.
        return model.isNotBlank() || durationMs > 0L || tokensIn > 0 || tokensOut > 0 ||
            block.contains("\"id\":\"answer\"") || block.contains("\"id\": \"answer\"")
    }

    /**
     * Records each request's own thinking time on the rows that request made.
     * The closing answer gets the last request's figure, and every tool call
     * gets the figure of the request that produced it, keyed by call id. This
     * is what keeps a turn with four tool rounds from putting the whole turn's
     * duration on one pill and leaving the other three with nothing.
     *
     * Only rows this attempt created — past the seq the thread ended on when it
     * was sent — are touched, so an old turn never borrows a new one's time.
     */
    suspend fun stampThinking(
        sid: String,
        afterSeq: Long,
        answerMs: Long,
        byCall: Map<String, Long>,
    ) {
        if (answerMs <= 0L && byCall.isEmpty()) return
        val rows = try { db.messages().allNow(sid) } catch (_: Exception) { return }
        var lastAnswer: MessageEntity? = null
        for (row in rows) {
            if (row.seq <= afterSeq) continue
            if (row.role == "model" || row.role == "assistant") lastAnswer = row
            if (!row.role.startsWith("tool") || byCall.isEmpty()) continue
            val callId = try { Block.parse(row.block)?.id.orEmpty() } catch (_: Exception) { "" }
            val ms = byCall[callId] ?: continue
            if (ms > 0L) {
                try { db.messages().stampThinkingAt(sid, row.seq, ms) } catch (_: Exception) { }
            }
        }
        val answer = lastAnswer
        if (answerMs > 0L && answer != null && answer.reasoningContent.isNotBlank()) {
            try { db.messages().stampThinkingAt(sid, answer.seq, answerMs) } catch (_: Exception) { }
        }
    }

    /**
     * Sends a message. Offline (or before the socket is ready) it is stored
     * with pending=true and flushed on the next successful connection.
     * Returns false only when an identical question is already queued unsent.
     * A repeat tap on the last, already-answered-or-failed question retries
     * that same row instead of stacking a twin bubble (Claude-like).
     */
    suspend fun send(
        sid: String,
        text: String,
        attachments: List<com.tulipskun.aixodia.data.model.AttachmentRef> = emptyList(),
    ): Boolean {
        val clean = text.trim()
        if (clean.isEmpty() && attachments.isEmpty()) return true
        // A New chat gets its server row on the first real send, not when the
        // button was pressed. If the daemon cannot take it, throw instead of
        // queueing a message into a session that does not exist there.
        if (socket.state.value == ConnState.ONLINE && isDraft(sid) && !ensureRemoteSession(sid, clean.take(60))) {
            throw IllegalStateException("Could not start this chat on the daemon")
        }
        try {
            val lastSeq = db.messages().maxSeq(sid)
            if (lastSeq > 0) {
                val last = db.messages().get(sid, lastSeq)
                if (last != null && last.role == "user" && last.text.trim() == clean) {
                    if (last.pending) return false
                    val clientMsgId = "$sid:${System.currentTimeMillis()}"
                    db.messages().upsert(last.copy(pending = true, clientMsgId = clientMsgId, createdAt = System.currentTimeMillis()))
                    // The frame reaching OkHttp's queue is not the daemon
                    // accepting it. Keep the row pending until the ack frame
                    // arrives; if the socket dies now, the reconnect flushes it.
                    socket.send(sid, clean, clientMsgId, attachments)
                    return true
                }
            }
        } catch (_: Exception) { }
        val dup = try {
            db.messages().allPending().any { it.sessionId == sid && it.role == "user" && it.text.trim() == clean }
        } catch (_: Exception) { false }
        if (dup) return false
        val clientMsgId = "$sid:${System.currentTimeMillis()}"
        val seq = db.messages().maxSeq(sid) + 1
        db.messages().upsert(
            MessageEntity(
                sessionId = sid,
                seq = seq,
                role = "user",
                text = clean,
                createdAt = System.currentTimeMillis(),
                pending = true,
                clientMsgId = clientMsgId,
            )
        )
        // queued == false → stays pending. queued == true also stays pending
        // until the daemon's ack: a close between send and ack must not lose
        // the question. The state collector flushes unacked rows on reconnect.
        socket.send(sid, clean, clientMsgId, attachments)
        return true
    }

    suspend fun flushPending() {
        val pending = db.messages().allPending()
        for ((sid, rows) in pending.groupBy { it.sessionId }) {
            // A message queued offline may belong to a chat whose first send
            // never reached the daemon. Create the row before flushing into it.
            if (isDraft(sid)) {
                val title = rows.firstOrNull { it.text.isNotBlank() }?.text?.take(60).orEmpty()
                if (!ensureRemoteSession(sid, title)) continue
            }
            for (m in rows) {
                if (m.clientMsgId.isBlank()) continue
                // Ack, not send, clears pending. A frame written into a dying
                // socket is still retried on the next reconnect.
                socket.send(m.sessionId, m.text, m.clientMsgId)
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
     * - different content: the counters diverged. D1 owns that seq. When the
     *   displaced local row's text is also in this page (its true twin), the
     *   page replaces it; otherwise the local row moves to the tail instead of
     *   keeping D1's row out. Either way no question disappears, and the next
     *   pull can still see every remote seq.
     *
     * A page that matches nothing local at all is a different case: the session
     * id was reborn server-side (deleted and recreated, so D1 holds a new
     * conversation under an old id). Merging would only stack two threads on
     * the same seqs, so D1 is adopted wholesale instead, keeping only pending
     * rows whose text D1 does not have yet (moved clear of D1's seqs).
     */
    private suspend fun reconcile(sid: String, rows: List<com.tulipskun.aixodia.data.remote.TurnRow>) {
        if (rows.isEmpty()) return
        // Collapse stacked repeat taps first: identical queued questions keep
        // only the earliest row, so a retry can never double the bubble.
        val queued = try { db.messages().allNow(sid) } catch (_: Exception) { emptyList() }
        for (group in queued.filter { it.pending && it.role == "user" }.groupBy { it.text.trim() }.values) {
            for (extra in group.sortedBy { it.seq }.drop(1)) {
                try { db.messages().deleteOne(sid, extra.seq) } catch (_: Exception) { }
            }
        }
        val entities = rows.map { it.toEntity(sid) }
        db.withTransaction {
            val localBySeq = db.messages().allNow(sid).associateBy { it.seq }
            // A page that matches nothing local at all means this session id was
            // reborn server-side (deleted and recreated, so D1 holds a new
            // conversation under an old id). Merging would only stack two threads
            // on the same seqs, so D1 is adopted wholesale instead: stale rows go,
            // pending rows stay only while their text is nowhere in the page
            // (still unsent), and a pending row D1 already has is dropped in
            // favour of D1's copy.
            var nextSeq = ((localBySeq.keys + entities.map { it.seq }).maxOrNull() ?: 0L) + 1L
            if (localBySeq.isNotEmpty() && entities.none { e ->
                    localBySeq[e.seq]?.let { it.role == e.role && it.text == e.text } == true
                }
            ) {
                val pulledTexts = entities.map { it.text }.toSet()
                val entitySeqs = entities.map { it.seq }.toSet()
                for (row in localBySeq.values) {
                    // Drop only local rows D1 already holds (by text) — they
                    // are duplicates. Keep pending rows and rows whose text is
                    // not in the pulled page: they may not have reached D1 yet,
                    // and deleting them makes the thread flicker or lose
                    // messages. A kept row may not sit on a seq D1 now owns.
                    if (row.text in pulledTexts) {
                        db.messages().deleteOne(sid, row.seq)
                    } else if (row.seq in entitySeqs) {
                        db.messages().deleteOne(sid, row.seq)
                        db.messages().upsert(row.copy(seq = nextSeq++))
                    }
                }
                for (e in entities.sortedBy { it.seq }) {
                    db.messages().upsert(e.copy(thinkingMs = localBySeq[e.seq]?.thinkingMs ?: 0))
                }
                return@withTransaction
            }
            for (e in entities.sortedBy { it.seq }) {
                val existing = localBySeq[e.seq]
                if (existing == null) {
                    db.messages().upsert(e)
                    continue
                }
                if (existing.role == e.role && existing.text == e.text) {
                    if (existing != e.copy(clientMsgId = existing.clientMsgId, thinkingMs = existing.thinkingMs)) {
                        // D1 knows nothing of the thinking time the phone
                        // measured, so adopting its row must not wipe it.
                        db.messages().upsert(e.copy(pending = false, clientMsgId = existing.clientMsgId, thinkingMs = existing.thinkingMs))
                    }
                    continue
                }
                val twinInPage = entities.any { it.seq != e.seq && it.text == existing.text }
                if (twinInPage) {
                    db.messages().deleteOne(sid, e.seq)
                    db.messages().upsert(e)
                } else {
                    // D1 owns this seq now. Keeping the old local row there used
                    // to leave the remote row uninserted, so the next pull could
                    // never repair the gap; move the local row to the tail and
                    // keep both, in seq order.
                    db.messages().deleteOne(sid, existing.seq)
                    db.messages().upsert(existing.copy(seq = nextSeq++))
                    db.messages().upsert(e)
                }
            }
        }
    }

    /** Deltas remain UI-only. Committed rows arrive with real D1 seqs at close. */
    private suspend fun onFrame(f: AiOutput) {
        if (f.kind == "ack" && f.clientMsgId.isNotBlank()) {
            db.messages().markAcked(f.clientMsgId)
        }
        if (f.sessionId.isBlank() || f.turns.isEmpty()) return
        val sid = f.sessionId
        val entities = f.turns.filter { it.seq > 0 }.map { it.toEntity(sid) }
        if (entities.isEmpty()) return
        db.withTransaction {
            val local = db.messages().allNow(sid)
            val merged = mergeCommittedTurns(local, entities, f.clientMsgId)
            val keptSeqs = merged.map { it.seq }.toSet()
            for (row in local) {
                if (row.seq !in keptSeqs) db.messages().deleteOne(sid, row.seq)
            }
            val localBySeq = local.associateBy { it.seq }
            for (row in merged) {
                if (localBySeq[row.seq] != row) db.messages().upsert(row)
            }
            val last = entities.lastOrNull { !it.role.startsWith("tool") } ?: entities.last()
            val cur = db.sessions().get(sid) ?: SessionEntity(id = sid)
            db.sessions().upsert(cur.copy(
                title = cur.title.takeIf { it.isNotBlank() && it != sid }
                    ?: entities.firstOrNull { it.role == "user" }?.text?.take(42) ?: sid,
                model = entities.lastOrNull { it.model.isNotBlank() }?.model ?: cur.model,
                lastSnippet = last.text.take(120),
                lastAt = last.createdAt,
            ))
        }
    }


    private fun com.tulipskun.aixodia.data.remote.TurnRow.toEntity(sid: String): MessageEntity {
        val view = toolRowView(role, text)
        return MessageEntity(
        sessionId = sid,
        seq = seq,
        role = normalizedRole(role),
        text = view.text,
        toolName = view.toolName,
        toolArgs = view.toolArgs,
        createdAt = if (createdAt > 0) createdAt * 1000 else System.currentTimeMillis(),
        agent = agent,
        jobId = jobId,
        // The footer travels with the turn, so a chat reopened tomorrow still
        // shows which model answered and what it cost.
        tokensIn = inputTokens,
        tokensOut = outputTokens,
        cacheRead = cacheRead,
        cacheWrite = cacheWrite,
        reasoningTokens = reasoningTokens,
        inputIncludesCache = inputIncludesCache,
        model = model,
        durationMs = durationMs,
        generationMs = generationMs,
        // D1 carries no thinking duration; the phone measures it while the
        // turn runs and stamps it afterwards, so a pulled row always starts
        // at zero here and the mirror keeps the local figure.
        thinkingMs = 0,
        // The final answer's reasoning arrives in the column; a tool round's
        // reasoning rides inside the call row's JSON, so fall back to it.
        reasoningContent = view.reasoning.ifBlank { reasoningContent },
        block = block,
        )
    }

    fun close() = socket.close()

}

/** The number of chats the daemon returns in one list page. */
internal const val SESSION_LIST_PAGE = 200

/**
 * Applies the daemon's chat metadata without throwing away phone-only state.
 *
 * D1 owns the title, model and updated_at, so those always move forward even
 * for a chat Room already holds. Unread and the last preview are local, and a
 * queued message keeps its chat at the top until the daemon stores it.
 */
internal fun mergeSessionRows(
    local: List<SessionEntity>,
    server: List<com.tulipskun.aixodia.data.remote.SessionRow>,
    pending: Set<String>,
): List<SessionEntity> {
    val known = local.associateBy { it.id }
    return server.map { row ->
        val prev = known[row.id]
        val serverAt = if (row.updatedAt > 0) row.updatedAt * 1000L else prev?.lastAt ?: 0L
        SessionEntity(
            id = row.id,
            title = row.title.ifBlank {
                prev?.title?.takeIf { it.isNotBlank() } ?: row.id
            },
            model = row.model,
            lastSnippet = prev?.lastSnippet.orEmpty(),
            lastAt = if (row.id in pending) maxOf(serverAt, prev?.lastAt ?: 0L) else serverAt,
            unread = prev?.unread ?: 0,
            // A draft whose create reached D1 just before the app died is a
            // server chat now; stop treating it as local-only.
            remote = true,
        )
    }
}

/** True for a model reply that has no text, no tool name and was not waiting to send. */
internal fun isEmptyAnswer(role: String, text: String, toolName: String, pending: Boolean): Boolean =
    role != "user" && !role.startsWith("tool") && text.isBlank() && toolName.isBlank() && !pending
