package com.tulipskun.aixodia.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.os.SystemClock
import com.tulipskun.aixodia.SettingsStore
import com.tulipskun.aixodia.data.model.AiOutput
import com.tulipskun.aixodia.data.model.AttachmentRef
import com.tulipskun.aixodia.data.model.Block
import com.tulipskun.aixodia.data.model.ChatMessage
import com.tulipskun.aixodia.data.model.ProviderStatus
import com.tulipskun.aixodia.data.model.ProviderView
import com.tulipskun.aixodia.data.model.StoredTotals
import com.tulipskun.aixodia.data.model.ToolStep
import com.tulipskun.aixodia.data.model.humanToolArgs
import com.tulipskun.aixodia.data.remote.ApprovalRequest
import com.tulipskun.aixodia.data.remote.ConnState
import com.tulipskun.aixodia.data.repo.CHAT_PAGE
import com.tulipskun.aixodia.data.repo.ChatRepository
import com.tulipskun.aixodia.data.repo.MessageWindow
import com.tulipskun.aixodia.ui.display.LiveUsage
import com.tulipskun.aixodia.ui.display.ThinkPhase
import com.tulipskun.aixodia.ui.display.TokenField
import com.tulipskun.aixodia.ui.display.toolStepFor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

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

/**
 * How long a sent turn may go without a single frame from the daemon — not even
 * an ack — before the screen says so and lets the next message through. A live
 * turn can legitimately think for longer than this, but it has been heard from
 * by then; this is only the dead-of-silence case.
 */
private const val TURN_SILENCE_MS = 45_000L

class ChatViewModel(
    private val repo: ChatRepository,
    private val settings: SettingsStore,
    initialSession: String,
    // The application context only, to open a picked file's stream for upload.
    private val appContext: android.content.Context? = null,
) : ViewModel() {
    /** One thing a live turn said or did, in the order it happened. */
    sealed interface LiveBlock {
        /**
         * The model's words before a tool round, as its own block. `id` is the
         * engine's block id when the frame carried one: a round that restates
         * the same narration block then replaces this entry rather than adding
         * a second copy of the same words under it.
         */
        data class Say(val text: String, val atMs: Long, val id: String = "") : LiveBlock
        /** A tool step: the call, then its result when it lands. */
        data class Act(val step: ToolStep) : LiveBlock
        /**
         * One provider request's wait and thought, in the live thread: the
         * waiting-for-first-byte phase, then the reasoning it exposed. A turn
         * with several tool rounds therefore wears several of these, in the
         * order they happened, instead of one block that keeps sinking to the
         * bottom of the thread.
         */
        data class Think(
            val phase: ThinkPhase,
            val reasoning: String = "",
            val startedAtMs: Long = 0L,
            val endedAtMs: Long = 0L,
            /**
             * The engine's request block this wait-and-thought belongs to
             * ("req1", "req2", …). Blank on the optimistic block send invents
             * before the engine announces the round, which the announcement
             * then adopts instead of doubling.
             */
            val id: String = "",
        ) : LiveBlock
        /**
         * The engine trimmed the prompt: whole older turns fell out of the
         * window to make this request fit. The marker says how many were kept,
         * how many fell, and what they were about.
         */
        data class Compact(
            val keptTurns: Int,
            val droppedTurns: Int,
            val summary: List<String>,
            val atMs: Long,
        ) : LiveBlock
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
        // A call that reports again while it runs must not restart its own
        // clock: the elapsed time is since the call started, not the report.
        val kept = (out[at] as LiveBlock.Act).step
        out[at] = LiveBlock.Act(step.copy(startedAtMs = kept.startedAtMs.takeIf { it > 0L } ?: step.startedAtMs))
        return out
    }

    /**
     * Opens a new provider request in the live thread: the block that counts the
     * wait for its first byte, the request's time-to-first-token. Every request
     * of a turn opens one of these, so a turn that runs tools shows a fresh
     * "Sending request to provider…" after each round instead of one block that
     * keeps sinking to the bottom.
     */
    private fun beginRequest(now: Long = SystemClock.elapsedRealtime()) {
        // The next request only starts once every tool of the round has
        // finished: a result that lands while a parallel call is still running
        // must not open the next request early.
        if (liveBlocks.value.any { it is LiveBlock.Act && !it.step.done }) return
        // One open wait per request: parallel results that all arrive late must
        // not stack a second "Sending request to provider…" onto the same one.
        val last = liveBlocks.value.lastOrNull()
        if (last is LiveBlock.Think && last.phase == ThinkPhase.WAITING) return
        liveBlocks.value = liveBlocks.value + LiveBlock.Think(phase = ThinkPhase.WAITING, startedAtMs = now)
    }

    /**
     * The open request has answered: it produced words or a tool call, so it is
     * no longer waiting or thinking. A request that reasoned keeps its block,
     * closed and showing what it thought; one that reasoned nothing is dropped
     * rather than left as a "Thought" over an empty body.
     */
    private fun settleOpenRequest(now: Long = SystemClock.elapsedRealtime()) {
        val blocks = liveBlocks.value
        val at = blocks.indexOfLast { it is LiveBlock.Think }
        if (at < 0) return
        val think = blocks[at] as LiveBlock.Think
        if (think.phase == ThinkPhase.DONE) return
        val out = blocks.toMutableList()
        if (think.phase == ThinkPhase.THINKING && think.reasoning.isNotBlank()) {
            out[at] = think.copy(phase = ThinkPhase.DONE, endedAtMs = now)
        } else {
            out.removeAt(at)
        }
        liveBlocks.value = out
    }

    /**
     * The chat currently on screen; set by ensureSession, like a chat app. The
     * id itself is a flow so the window and the totals follow a chat switch
     * without a second view model having to be created.
     */
    private val activeId = MutableStateFlow(initialSession)

    /**
     * The open chat as a flow. The screen resets its scroll state when this
     * changes, so switching chats starts at the newest row instead of keeping
     * the previous chat's position.
     */
    val activeSession: StateFlow<String> = activeId.asStateFlow()

    var sessionId: String
        get() = activeId.value
        private set(value) {
            if (activeId.value == value) return
            // A different chat starts at its newest page, not at the old
            // chat's floor. Close the window first, then switch the id, so the
            // new chat is never queried with the old chat's lower bound.
            windowFloor.value = null
            hasOlder.value = false
            activeId.value = value
        }

    /**
     * The oldest seq the screen maps. Null until the newest page is read; Room
     * then keeps this window live as new turns arrive, without touching the
     * rows below it.
     */
    private val windowFloor = MutableStateFlow<Long?>(null)
    /**
     * The seq the in-flight turn starts above, as an observable: -1 between
     * turns. The engine stores a turn's narration and tool rows as they happen,
     * so those rows are in Room while the live timeline is still the thing
     * drawing them, and drawing both put every tool card and every narration
     * line on screen twice until the answer landed. While this is >= 0 the
     * thread hides the rows it created and lets the live timeline own them; the
     * hand-over is one emission, because clearing the live turn sets it back to
     * -1 after the live blocks have gone.
     */
    private val _turnBaseSeq = MutableStateFlow(-1L)

    /** The stored rows of this chat's window, exactly as the repository has them. */
    private val storedRows: Flow<List<ChatMessage>> =
        combine(activeId, windowFloor) { sid, from -> sid to from }
            .flatMapLatest { (sid, from) ->
                if (from == null) flowOf(emptyList()) else repo.observeMessages(sid, from)
            }

    /**
     * What the thread draws. Same rows as [storedRows] while nothing runs;
     * during a turn, the rows that turn is still writing are withheld because
     * the live timeline is showing them.
     *
     * The question itself stays: the daemon stores it before it answers, and a
     * thread that hid the user's own message would empty out at the moment they
     * pressed send.
     */
    val messages: Flow<List<ChatMessage>> =
        combine(storedRows, _turnBaseSeq) { rows, base -> threadRows(rows, base) }

    /**
     * Whole-chat cost from the engine's session metadata, not from the page of
     * rows on screen. The ViewModel only relays it; the strip has no aggregate
     * of its own to drift from the daemon's.
     */
    val sessionUsage: Flow<StoredTotals?> =
        activeId.flatMapLatest { repo.observeSessionUsage(it) }

    /** Whether a scroll to the top should ask for another page. */
    val hasOlder = MutableStateFlow(false)
    val loadingOlder = MutableStateFlow(false)
    /** True once the server has said there is nothing above this chat. */
    private var olderExhausted = false

    val sessions = repo.observeSessions()
    val conn: StateFlow<ConnState> = repo.connState

    /** Why the socket is down, straight from the daemon's HTTP answer. */
    val socketError = repo.socketError
    val approval = repo.approval

    /**
     * The footer's field list for the provider in use. Switched on the endpoint
     * rather than read once, because settings.current() is suspend and the route
     * can change under a live socket.
     */
    val tokenFields: Flow<List<TokenField>> =
        settings.endpointFlow.flatMapLatest { settings.tokenFieldsFlow(it) }

    /** Latest live status line for the thread ("web_fetch …"). */
    val status = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val notice = MutableStateFlow("")

    /**
     * Deltas stay UI-only; committed WS rows replace them when the turn closes.
     */
    val liveText = MutableStateFlow("")
    /** Actual upstream reasoning; never filled with status messages. */
    val liveReasoning = MutableStateFlow("")
    val reasoningActive = MutableStateFlow(false)
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
    val providers = MutableStateFlow<List<ProviderView>>(emptyList())
    /** Same catalogue's global health/key counts, shown as a caption in the session sheet. */
    val providerStatuses = MutableStateFlow<List<ProviderStatus>>(emptyList())

    /** Model, tokens, elapsed time and rate for the footer. */
    val turnStats = MutableStateFlow(TurnStats())
    /**
     * The running cost of the turn in flight, restated by the engine after
     * every provider round. The strip draws it in place of the turn's own
     * stored row until that row lands, so the figures climb with the rounds
     * instead of jumping once when the turn ends. Null between turns.
     */
    val liveUsage = MutableStateFlow<LiveUsage?>(null)
    /** Unix second the daemon process started; 0 = unknown/not polled yet. */
    val daemonStartedAt = MutableStateFlow(0L)
    /** Files picked and uploaded, waiting to go out with the next message. */
    val pendingAttachments = MutableStateFlow<List<AttachmentRef>>(emptyList())
    /** True while an upload is in flight, so the picker can be disabled. */
    val uploading = MutableStateFlow(false)
    /** The error to show as a retryable card, or "" when nothing failed. */
    val errorCard = MutableStateFlow("")
    /**
     * The open chat's goals and plans with their statuses, for the goals card.
     * Null until the engine first answers; refreshed on open, after a turn,
     * and whenever a goal or plan tool reports a result.
     */
    val objectives = MutableStateFlow<com.tulipskun.aixodia.data.remote.ObjectivesPage?>(null)
    private var objectivesJob: Job? = null

    /** Re-reads the goals card. The newest request wins; stale reads are cut. */
    private fun refreshObjectives(sid: String = sessionId) {
        if (sid.isBlank()) return
        objectivesJob?.cancel()
        objectivesJob = viewModelScope.launch {
            // The engine writes D1 right after the tool result frame; a moment's
            // delay keeps the card from re-reading the list before the write lands.
            delay(400)
            val page = repo.objectives(sid)
            if (sid == sessionId && page != null) objectives.value = page
        }
    }
    /**
     * True once the daemon's closing message has replaced the streamed text.
     * A delta arriving after that would be appended to the finished answer and
     * read as a repetition, so it is dropped.
     */
    private var answerSettled = false
    private var liveChars = 0
    /**
     * Where the thread ended when the turn was sent, so the thinking time it
     * measured can be stamped on just the rows it created. -1 when no turn is
     * in flight. Declared with [messages] above, which reads it.
     */
    private var turnBaseSeq: Long
        get() = _turnBaseSeq.value
        set(value) {
            _turnBaseSeq.value = value
        }
    /**
     * The reconnect watch: after a dropped socket it polls D1 until the turn
     * that kept running on the daemon lands, and retries a turn that really
     * died. One job per chat, cancelled as soon as the answer arrives.
     */
    private var recoveryJob: Job? = null
    /**
     * The switch in flight. A second tap must cancel the first one before it
     * reaches the daemon, otherwise the older switch can set the socket and the
     * route back to the chat the reader already left.
     */
    private var switchJob: Job? = null
    private var lastRecoveryRetry = 0L
    /**
     * When the daemon last said anything about the turn in flight, and whether
     * it has said anything at all. A turn whose message never reaches the
     * daemon leaves [busy] set, and every later send is refused with a one-line
     * notice — which reads as the assistant having stopped answering. The
     * watcher in `init` uses these two to tell that apart from a turn that is
     * simply thinking.
     */
    private var lastFrameAt = 0L
    private var turnHeard = false
    val selectedProvider = MutableStateFlow("")
    val selectedModel = MutableStateFlow("")
    /** Whether the open chat has a stored pin, as opposed to a draft selection. */
    val hasStoredRoute = MutableStateFlow(false)
    /** A route chosen for a New chat, saved to the daemon with its first send. */
    private var draftRoute: Pair<String, String>? = null

    init {
        // A fresh install has no settings yet, and every call here is network
        // facing: failures belong in [notice], never as an uncaught exception
        // (an uncaught one in viewModelScope takes the whole process down).
        viewModelScope.launch {
            // Cache first: the stored chat's newest page is on screen before
            // any network read. ensureSession below may then open the same
            // chat again (and refresh its tail); the second window is only a
            // re-anchor, not what makes the first frame appear.
            if (initialSession.isNotBlank()) startWindow(initialSession)
            runCatching {
                repo.syncSessions()
                // No session id to type: open the last chat, or start one.
                val id = repo.ensureSession(initialSession)
                sessionId = id
                settings.saveSession(id)
                startWindow(id)
            }.onFailure { err ->
                notice.value = "Cannot connect: ${err.message ?: err::class.simpleName}"
            }
            // The header shows the route this chat actually uses.
            runCatching { repo.sessionRoute(sessionId) }.getOrNull()?.let { (provider, model) ->
                selectedProvider.value = provider
                selectedModel.value = model
                hasStoredRoute.value = true
            }
            // A cold start can already have an online socket, so the
            // connection collector never sees an OFFLINE -> ONLINE edge.
            recoverInterruptedTurn()
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
            // Opening or switching a chat re-reads its goals card; the card
            // belongs to the chat on screen, never to the one left behind.
            activeId.collect { sid ->
                if (sid.isNotBlank()) objectives.value = null
                if (sid.isNotBlank()) refreshObjectives(sid)
            }
        }
        viewModelScope.launch {
            var wasOnline = repo.connState.value == ConnState.ONLINE
            repo.connState.collect { state ->
                if (state == ConnState.ONLINE) {
                    refreshDaemonStart()
                    // First open and every reconnect share this path: a turn
                    // that was cut off either lands in D1 shortly or is retried.
                    if (!wasOnline) recoverInterruptedTurn()
                }
                wasOnline = state == ConnState.ONLINE
            }
        }
        // A reconnect does not replay the daemon's `done` frame, so the live
        // bubble would stay busy forever even after the answer is in D1. The
        // committed answer after the last question is the signal to hand over.
        // It reads the stored rows and not [messages], because while a turn
        // runs the thread's own list withholds exactly the rows being watched:
        // watching that would mean the hand-over never happened and the live
        // turn would sit there forever after the daemon had finished it.
        viewModelScope.launch {
            storedRows.collect { rows ->
                if (!busy.value) return@collect
                val lastUser = rows.indexOfLast { it.role == "user" }
                if (lastUser < 0) return@collect
                // Narration before a tool round is stored as an assistant row
                // too. Only the closing answer carries usage, duration, or the
                // engine's `answer` block id.
                val answered = rows.drop(lastUser + 1).any {
                    (it.role == "model" || it.role == "assistant") &&
                        (it.model.isNotBlank() || it.durationMs > 0L ||
                            it.tokensIn > 0 || it.tokensOut > 0 ||
                            it.block.contains("\"id\":\"answer\"") ||
                            it.block.contains("\"id\": \"answer\""))
                }
                if (answered) {
                    recoveryJob?.cancel()
                    errorCard.value = ""
                    clearLiveTurn()
                }
            }
        }
        viewModelScope.launch {
            repo.liveFrames.collect { f ->
                if (f.sessionId.isNotEmpty() && f.sessionId != sessionId) return@collect
                // Any frame, an ack included, means the daemon has the turn.
                lastFrameAt = SystemClock.elapsedRealtime()
                turnHeard = true
                when (f.kind) {
                    // One streamed chunk: append to the bubble the phone is
                    // already showing.
                    "delta" -> onDelta(f.agent, f.text)
                    // The authoritative answer. A streamed turn already filled
                    // the bubble, so this only confirms it.
                    "message" -> onMessage(f.text, f)
                    "trace" -> onTrace(f)
                    "done" -> onTurnDone(f)
                    "error" -> {
                        // A resend can reach the daemon while the original turn
                        // still runs. That is not a failure: the ack cleared the
                        // pending row, and the reconnect watch will collect the
                        // answer when it lands.
                        if (f.text.contains("turn_in_progress") ||
                            f.text.contains("already running for this chat")
                        ) {
                            notice.value = "The daemon is still working on this chat…"
                            recoverInterruptedTurn()
                            return@collect
                        }
                        // The rows the failed attempt already stored are here
                        // now. Stamp the thought times they did get before the
                        // live timeline that measured them is cleared.
                        val spans = liveThinkingSpans()
                        val sid = f.sessionId.ifBlank { sessionId }
                        val baseSeq = turnBaseSeq
                        status.value = ""
                        liveText.value = ""
                        discardDeltas()
                        liveBlocks.value = emptyList()
                        liveThinkingMs.value = 0L
                        liveReasoning.value = ""
                        reasoningActive.value = false
                        turnBaseSeq = -1L
                        busy.value = false
                        liveUsage.value = null
                        // Raw daemon text is logged by the daemon; the user sees
                        // this app's wording. A failure the daemon can retry gets
                        // a card with a Retry button; a chat it does not have gets
                        // a fresh chat instead, because Retry there is refused
                        // every single time.
                        val msg = friendlyError(f.text)
                        if (isMissingSession(f.text)) {
                            errorCard.value = ""
                            notice.value = msg
                            dropDeadChat(sid.ifBlank { sessionId })
                        } else {
                            errorCard.value = msg
                            notice.value = msg
                        }
                        finishStats()
                        if (baseSeq >= 0L) {
                            viewModelScope.launch {
                                runCatching {
                                    repo.stampThinking(sid, baseSeq, spans.answerMs, spans.byCall)
                                }
                            }
                        }
                    }
                    // The ack only means the message reached the daemon. The
                    // turn keeps running until `done`, and until then the stop
                    // button must stay on screen.
                    "ack" -> { notice.value = "" }
                }
            }
        }
        // A turn the daemon never acknowledged is the one case where the screen
        // would otherwise stay busy forever: the question is on disk, the
        // composer refuses the next one, and nothing on screen says why. Say it,
        // and hand the next attempt back to the person.
        viewModelScope.launch {
            while (true) {
                delay(5_000)
                if (!busy.value || lastFrameAt == 0L || turnHeard) continue
                if (SystemClock.elapsedRealtime() - lastFrameAt < TURN_SILENCE_MS) continue
                clearLiveTurn()
                val msg = "The daemon did not answer. The question is kept — tap Retry when it is back."
                errorCard.value = msg
                notice.value = msg
            }
        }
    }

    private fun onDelta(agent: String, chunk: String) {
        if (chunk.isEmpty() || answerSettled) return
        liveThinkingMs.value = 0L
        reasoningActive.value = false
        liveAgent.value = agent.ifEmpty { "main" }
        // The request has spoken: it is no longer waiting or thinking.
        settleOpenRequest()
        // A fast model streams a token per frame; restating the whole bubble
        // per token made the thread lag behind the answer. Coalesce into the
        // flow on a short timer instead.
        deltaBuffer.append(chunk)
        scheduleDeltaFlush()
        liveChars += chunk.length
        // The clock for the rate starts here, not when the question was sent.
        val stats = turnStats.value
        if (stats.firstTokenAtMs == 0L) {
            turnStats.value = stats.copy(firstTokenAtMs = SystemClock.elapsedRealtime())
        }
        estimateTokens()
        busy.value = true
    }

    private val deltaBuffer = StringBuilder()
    private var deltaFlushJob: Job? = null

    private fun scheduleDeltaFlush() {
        if (deltaFlushJob?.isActive == true) return
        deltaFlushJob = viewModelScope.launch {
            delay(120)
            flushDeltas()
        }
    }

    /** Puts every buffered token on screen at once. Safe to call any time. */
    private fun flushDeltas() {
        deltaFlushJob?.cancel()
        deltaFlushJob = null
        if (deltaBuffer.isEmpty()) return
        liveText.value += deltaBuffer.toString()
        deltaBuffer.setLength(0)
    }

    /** Drops buffered tokens the screen will never show — a cleared turn. */
    private fun discardDeltas() {
        deltaFlushJob?.cancel()
        deltaFlushJob = null
        deltaBuffer.setLength(0)
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

    private fun onMessage(text: String, frame: AiOutput? = null) {
        recordUsage(frame)
        // The closing frame names the seq the turn's answer lands at. The strip
        // uses it to skip that row once it reaches the thread, so the running
        // total is never counted alongside the row it becomes.
        if (frame != null && frame.seq > 0L) {
            liveUsage.value = liveUsage.value?.copy(seq = frame.seq)
        }
        if (text.isBlank()) return
        // Replace whatever streamed in with the daemon's own copy: it is the one
        // that also lands in D1.
        answerSettled = true
        // A turn that never streamed still answered: close the open request so
        // its wait does not outlive the answer.
        settleOpenRequest()
        discardDeltas()
        liveText.value = text
        liveThinkingMs.value = 0L
        reasoningActive.value = false
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
        // it took, which is what the footer of that message says. The
        // route stays as the fallback while the answer is still streaming.
        val model = frame.model.ifBlank { stats.model }
        if (input <= 0 && output <= 0 && cacheRead <= 0 && cacheWrite <= 0 && frame.reasoningTokens <= 0 && frame.seq == 0L) {
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

    /** Status is separate from upstream reasoning. */
    private fun traceNote(line: String) {
        if (line.isBlank()) return
        status.value = line
    }

    /**
     * One live frame as the engine's display block. Every branch upserts by
     * block id — the request's own block, the call's own id — so the live
     * thread is the stored thread before it is stored, instead of a second
     * guess at it built from stage order. True when the frame carried a
     * block; false takes the stage path for older daemons.
     */
    private fun onBlockFrame(f: AiOutput): Boolean {
        val block = f.block ?: return false
        val now = SystemClock.elapsedRealtime()
        when (block.type) {
            Block.REQUEST -> {
                if (block.state == "done") settleRequest(block.id, now)
                else openRequest(block.id, now)
            }
            Block.REASONING -> {
                val chunk = block.text
                if (chunk.isEmpty()) return true
                liveReasoning.value = (liveReasoning.value + chunk).takeLast(32 * 1024)
                reasoningActive.value = true
                // The reasoning belongs to the request named on the block, not
                // to the turn as a whole: it grows that request's own block.
                val blocks = liveBlocks.value
                val at = blocks.indexOfFirst { it is LiveBlock.Think && it.id == block.id }
                liveBlocks.value = if (at >= 0) {
                    val think = blocks[at] as LiveBlock.Think
                    val out = blocks.toMutableList()
                    out[at] = think.copy(
                        phase = ThinkPhase.THINKING,
                        reasoning = (think.reasoning + chunk).takeLast(32 * 1024),
                        startedAtMs = think.startedAtMs.takeIf { it > 0L } ?: now,
                    )
                    out
                } else {
                    blocks + LiveBlock.Think(
                        id = block.id,
                        phase = ThinkPhase.THINKING,
                        reasoning = chunk.takeLast(32 * 1024),
                        startedAtMs = now,
                    )
                }
            }
            Block.TOOL -> {
                if (block.state == "done" || block.state == "error") completeLiveTool(block)
                else startLiveTool(block, now)
            }
            Block.TEXT -> {
                if (block.text.isNotBlank()) {
                    // The engine names each piece of narration, so a round that
                    // restates its block replaces it in place. Appending would
                    // leave the same words on screen twice, and the second copy
                    // is the one the thread scrolls to.
                    val blocks = liveBlocks.value
                    val at = if (block.id.isBlank()) -1 else
                        blocks.indexOfFirst { it is LiveBlock.Say && it.id == block.id }
                    liveBlocks.value = if (at >= 0) {
                        val said = blocks[at] as LiveBlock.Say
                        blocks.toMutableList().also { it[at] = said.copy(text = block.text) }
                    } else {
                        blocks + LiveBlock.Say(block.text, System.currentTimeMillis(), block.id)
                    }
                }
            }
            Block.STATUS -> traceNote(block.text)
            else -> if (block.text.isNotBlank()) traceNote(block.text)
        }
        return true
    }

    /**
     * The engine opened a provider request round: its wait-for-first-byte
     * block. The guards are beginRequest's — no open wait while a tool still
     * runs, no second wait on the same round — keyed by the block id instead
     * of position. The optimistic block send invented is adopted, not doubled.
     */
    private fun openRequest(id: String, now: Long) {
        if (liveBlocks.value.any { it is LiveBlock.Act && !it.step.done }) return
        val blocks = liveBlocks.value
        val waiting = blocks.indexOfLast { it is LiveBlock.Think && it.phase == ThinkPhase.WAITING }
        if (waiting >= 0) {
            val think = blocks[waiting] as LiveBlock.Think
            if (think.id == id) return
            if (think.id.isBlank()) {
                val out = blocks.toMutableList()
                out[waiting] = think.copy(id = id, startedAtMs = think.startedAtMs.takeIf { it > 0L } ?: now)
                liveBlocks.value = out
                return
            }
            settleRequest(think.id, now)
        }
        liveBlocks.value = liveBlocks.value + LiveBlock.Think(id = id, phase = ThinkPhase.WAITING, startedAtMs = now)
    }

    /**
     * The engine closed a provider request round: the round spoke or its tools
     * landed, so it is no longer waiting. A round that reasoned keeps its
     * block, closed and showing what it thought; one that reasoned nothing is
     * dropped rather than left as a thought over an empty body.
     */
    private fun settleRequest(id: String, now: Long = SystemClock.elapsedRealtime()) {
        val blocks = liveBlocks.value
        val at = blocks.indexOfLast { it is LiveBlock.Think && (it.id == id || it.id.isBlank()) }
        if (at < 0) return
        val think = blocks[at] as LiveBlock.Think
        if (think.phase == ThinkPhase.DONE) return
        val out = blocks.toMutableList()
        if (think.phase == ThinkPhase.THINKING && think.reasoning.isNotBlank()) {
            out[at] = think.copy(phase = ThinkPhase.DONE, endedAtMs = now)
        } else {
            out.removeAt(at)
        }
        liveBlocks.value = out
    }

    /** A tool call the engine announced: the round spoke, so it settles. */
    private fun startLiveTool(block: Block, now: Long) {
        reasoningActive.value = false
        if (block.name.isBlank()) return
        if (block.round > 0) settleRequest(Block.requestId(block.round), now)
        else settleOpenRequest(now)
        liveThinkingMs.value = 0L
        // What came before this tool is its own turn, now and after.
        flushDeltas()
        val said = liveText.value.trim()
        if (said.isNotEmpty()) {
            liveBlocks.value = liveBlocks.value +
                LiveBlock.Say(said, System.currentTimeMillis())
            liveText.value = ""
        }
        liveBlocks.value = upsertStep(liveBlocks.value, toolStepFor(block, "", now))
        traceNote("${block.name}…")
    }

    /**
     * A tool result the engine announced: the step named by the call id is
     * completed, lending it the arguments the result frame never carries.
     * The next request is the engine's to open — unlike the old stage path,
     * nothing here invents one.
     */
    private fun completeLiveTool(block: Block) {
        liveThinkingMs.value = 0L
        val key = block.id.ifBlank { block.name }
        val known = liveBlocks.value.filterIsInstance<LiveBlock.Act>()
            .firstOrNull { it.step.key == key }?.step?.args.orEmpty()
        liveBlocks.value = liveBlocks.value.map { item ->
            if (item is LiveBlock.Act && item.step.key == key) {
                item.copy(
                    step = item.step.copy(
                        args = humanToolArgs(block.args).ifBlank { known },
                        result = block.result,
                        isError = block.isError,
                        done = true,
                        durationMs = block.durationMs,
                    ),
                )
            } else {
                item
            }
        }
        val note = if (block.isError) "${block.name} failed" else "${block.name} done"
        status.value = note
        traceNote(note)
        if (isObjectiveTool(block.name)) refreshObjectives()
    }

    /** True for the tools that change the goals card. */
    private fun isObjectiveTool(name: String) =
        name.startsWith("goal_") || name.startsWith("plan_")

    /**
     * A provider round finished: adopt the turn's running cost. The engine
     * sends the sum so far, not the round's own figures, so a frame that never
     * arrives is corrected by the next one. The answer's seq is the one piece
     * the usage frame does not carry, so it is kept from whatever is already
     * known and filled in when the closing message names it.
     */
    private fun onUsage(f: AiOutput) {
        liveUsage.value = LiveUsage(
            seq = liveUsage.value?.seq ?: 0L,
            promptTokens = f.inputTokens,
            cacheRead = f.cacheRead,
            cacheWrite = f.cacheWrite,
            outputTokens = f.outputTokens,
            inputIncludesCache = f.inputIncludesCache,
        )
    }

    private fun onTrace(f: AiOutput) {
        // A provider round finished: the engine restates the turn's running
        // cost. It moves the strip only, so it never touches the thread.
        if (f.stage == "usage") {
            onUsage(f)
            return
        }
        // The engine trimmed the prompt. The marker lands where it happened in
        // the thread; the digest says what fell out of the window.
        if (f.stage == "compact") {
            liveBlocks.value = liveBlocks.value + LiveBlock.Compact(
                keptTurns = f.keptTurns,
                droppedTurns = f.droppedTurns,
                summary = f.compactSummary,
                atMs = SystemClock.elapsedRealtime(),
            )
            return
        }
        // The engine's display block, when the frame has one: it names the
        // round or step outright, so the live thread is upserted by id instead
        // of re-derived from stage order. Frames from an older daemon carry
        // nothing here and take the stage path below.
        if (onBlockFrame(f)) return
        when (f.stage) {
            "reasoning" -> {
                val chunk = f.text
                liveReasoning.value = (liveReasoning.value + chunk).takeLast(32 * 1024)
                reasoningActive.value = true
                // The reasoning belongs to the request that is open now, not to
                // the turn as a whole: it grows that request's own block, and a
                // new request opens a new block.
                val now = SystemClock.elapsedRealtime()
                val blocks = liveBlocks.value
                val at = blocks.indexOfLast { it is LiveBlock.Think }
                liveBlocks.value = if (at >= 0) {
                    val think = blocks[at] as LiveBlock.Think
                    val out = blocks.toMutableList()
                    out[at] = think.copy(
                        phase = ThinkPhase.THINKING,
                        reasoning = (think.reasoning + chunk).takeLast(32 * 1024),
                        startedAtMs = think.startedAtMs.takeIf { it > 0L } ?: now,
                    )
                    out
                } else {
                    blocks + LiveBlock.Think(
                        phase = ThinkPhase.THINKING,
                        reasoning = chunk.takeLast(32 * 1024),
                        startedAtMs = now,
                    )
                }
            }
            "tool_call", "tool_running" -> {
                reasoningActive.value = false
                val name = f.toolCall?.name ?: return
                settleOpenRequest()
                liveThinkingMs.value = 0L
                // What came before this tool is its own turn, now and after.
                flushDeltas()
                val said = liveText.value.trim()
                if (said.isNotEmpty()) {
                    liveBlocks.value = liveBlocks.value +
                        LiveBlock.Say(said, System.currentTimeMillis())
                    liveText.value = ""
                }
                liveBlocks.value = upsertStep(liveBlocks.value, ToolStep(
                        id = f.toolCall?.id.orEmpty(),
                        name = name,
                        args = humanToolArgs(f.toolCall?.arguments.orEmpty()),
                        durationMs = f.toolDurationMs,
                        startedAtMs = SystemClock.elapsedRealtime(),
                    ))
                traceNote("$name…")
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
                                result = result,
                                isError = f.toolResult?.isError ?: false,
                                done = true,
                                durationMs = f.toolDurationMs,
                            )
                        )
                    } else {
                        block
                    }
                }
                status.value = if (f.toolResult?.isError == true) "$name failed" else "$name done"
                traceNote(status.value)
                if (isObjectiveTool(name)) refreshObjectives()
                // The result goes back to the provider and the next request
                // starts: a new "Sending request to provider…" for the next
                // round, so each request's wait and thought is its own.
                beginRequest()
            }
            "response_text" -> {
                liveThinkingMs.value = f.reasoningMs
                traceNote(
                    if (f.reasoningMs > 0) {
                        "Thinking · " + String.format(Locale.US, "%.1fs", f.reasoningMs / 1000.0)
                    } else {
                        "Thinking…"
                    },
                )
            }
            "retry_wait" -> traceNote("Retrying…")
            "request" -> traceNote("Sending to provider…")
            "provider_ready" -> traceNote("Provider accepted, processing")
            else -> traceNote(f.text.ifEmpty { f.stage })
        }
    }

    /** Answers the run's pending mutating tool. */
    fun approve(runId: String, callId: String, allow: Boolean, reason: String = "") {
        repo.approve(runId, callId, allow, reason)
    }

    private fun onTurnDone(frame: AiOutput) {
        val stage = frame.stage
        if (stage == "cancelled") {
            notice.value = "Stopped"
        } else if (stage == "already_done") {
            notice.value = "That task already finished"
        }
        // Each request measured its own thought in the live timeline. Its
        // rows only just arrived, so stamp the figures now while their seqs
        // and call ids are known: the answer gets the last request's time and
        // every tool row gets the time of the request that produced it.
        val sid = frame.sessionId.ifBlank { sessionId }
        val baseSeq = turnBaseSeq
        val spans = liveThinkingSpans()
        clearLiveTurn()
        answerSettled = false
        // The turn is over: let the running total go. Its answer's stored row
        // carries the same figures (and the turn's duration, which a running
        // total has no way to report), so the strip hands over to the thread.
        liveUsage.value = null
        // Repository has already committed the WS rows. A D1 read is only a
        // compatibility/recovery fallback for a daemon without a complete batch.
        viewModelScope.launch {
            if (!frame.turnsSynced) runCatching { repo.refreshLatest(sid) }
            if (baseSeq >= 0L) {
                runCatching { repo.stampThinking(sid, baseSeq, spans.answerMs, spans.byCall) }
            }
            // The cost strip now comes from the session metadata, so refresh
            // it after the turn: the running total has just been released and
            // the engine's sum is what keeps the new turn on screen.
            runCatching { repo.syncSessions() }
            // The turn may have moved goals and plans; the card shows the
            // engine's list, not the phone's guess.
            refreshObjectives(sid)
        }
    }

    /**
     * After a reconnect, a turn started before the drop may still be running on
     * the daemon. Poll the authoritative store instead of assuming the missing
     * `done` frame means failure. If the question is still unanswered after a
     * grace period, ask the daemon to resume it; the gateway refuses a second
     * concurrent turn, and that refusal just means the first one is alive.
     */
    private fun recoverInterruptedTurn() {
        if (sessionId.isBlank() || recoveryJob?.isActive == true) return
        recoveryJob = viewModelScope.launch {
            var attempts = 0
            // Give the socket's own refresh/verify a moment to land an answer
            // that was written while the phone was away.
            delay(4_000)
            repeat(72) { // six minutes at five-second intervals
                val sid = sessionId
                if (sid.isBlank() || repo.isDraft(sid)) return@launch
                runCatching { repo.refreshLatest(sid) }
                if (!repo.hasUnfinishedTurn(sid)) {
                    if (busy.value) {
                        errorCard.value = ""
                        clearLiveTurn()
                    }
                    return@launch
                }
                val now = System.currentTimeMillis()
                val age = now - repo.lastUserCreatedAt(sid)
                if (age >= 15_000L && attempts < 3 && now - lastRecoveryRetry >= 45_000L) {
                    attempts++
                    lastRecoveryRetry = now
                    if (busy.value) clearLiveTurn()
                    beginLiveTurn()
                    turnBaseSeq = repo.maxSeq(sid)
                    notice.value = "Retrying the unfinished message…"
                    if (!repo.retryTurn(sid)) {
                        clearLiveTurn()
                        notice.value = "Could not retry — the daemon is offline"
                        return@launch
                    }
                }
                delay(5_000)
            }
        }
    }

    /**
     * Takes the in-flight turn off the screen: the streamed text, the tool
     * cards, the status line and the busy flag. Any rows the turn produced are
     * already in D1; this only clears the live copy, so it is safe to call
     * whether the daemon confirmed the end or the screen is being unstuck.
     */
    private fun clearLiveTurn() {
        status.value = ""
        liveBlocks.value = emptyList()
        liveThinkingMs.value = 0L
        liveReasoning.value = ""
        reasoningActive.value = false
        busy.value = false
        finishStats()
        turnBaseSeq = -1L
        liveText.value = ""
        discardDeltas()
        // Nothing is in flight, so the silence watcher has nothing to judge.
        lastFrameAt = 0L
        turnHeard = false
    }

    /** Freezes the footer's numbers when the turn ends. */
    private fun finishStats() {
        val stats = turnStats.value
        if (stats.startedAtMs == 0L) return
        turnStats.value = stats.copy(running = false, endedAtMs = SystemClock.elapsedRealtime())
    }

    /**
     * The stop button. It takes the turn off the screen at once — the user
     * asked to stop, and a screen that keeps showing a turn the daemon has
     * nothing running for is the bug this fixes — then tells the daemon to
     * cancel. The daemon answers a cancel even when nothing is running, so a
     * done frame that follows only confirms the stop. Clearing locally is what
     * makes the button work when the socket is down, where no cancel can be
     * delivered at all: a stop that only changed a status line left the thread
     * stuck busy forever.
     */
    fun stop() {
        val sent = repo.stopTurn(sessionId)
        clearLiveTurn()
        answerSettled = true
        notice.value = if (sent) "Stopped" else "Socket offline — clearing stuck state instead"
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
            notice.value = "Select a provider and model first"
            return
        }
        viewModelScope.launch {
            // A New chat has no server row yet. Keep the choice here; send
            // pins it in the same breath as creating the row.
            if (repo.isDraft(sessionId)) {
                draftRoute = provider to model
                hasStoredRoute.value = true
                notice.value = "Using $provider / $model for this chat"
                return@launch
            }
            val ok = runCatching { repo.setSessionModel(sessionId, provider, model) }.getOrDefault(false)
            if (ok) {
                hasStoredRoute.value = true
                notice.value = "Using $provider / $model for this chat"
            } else {
                notice.value = "Failed to save provider/model"
            }
        }
    }

    /** Returns the open chat to the global agent defaults and forgets its pin. */
    fun clearModel() {
        if (!hasStoredRoute.value) {
            notice.value = "This chat already uses the agent default"
            return
        }
        viewModelScope.launch {
            if (repo.isDraft(sessionId)) {
                draftRoute = null
                selectedProvider.value = ""
                selectedModel.value = ""
                hasStoredRoute.value = false
                notice.value = "Cleared this chat model lock — using agent default"
                return@launch
            }
            val ok = runCatching { repo.clearSessionModel(sessionId) }.getOrDefault(false)
            if (ok) {
                selectedProvider.value = ""
                selectedModel.value = ""
                hasStoredRoute.value = false
                notice.value = "Cleared this chat model lock — using agent default"
            } else {
                notice.value = "Failed to clear the model lock"
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
                notice.value = "Upload failed for $failed of ${uris.size} files"
            }
            uploading.value = false
        }
    }

    fun removeAttachment(index: Int) {
        pendingAttachments.value = pendingAttachments.value.filterIndexed { i, _ -> i != index }
    }

    /** How long each request in the live timeline thought, split by call. */
    private data class ThinkingSpans(
        val answerMs: Long,
        val byCall: Map<String, Long>,
    )

    /**
     * Reads the live timeline once, before it is cleared, and assigns every
     * closed thought to the card it belongs to: the tools that request called
     * and, when it is the last one, the closing answer. A thought is never
     * merged into the turn total, so one round cannot put the whole turn's
     * clock on the final pill while the earlier pills show nothing.
     */
    private fun liveThinkingSpans(now: Long = SystemClock.elapsedRealtime()): ThinkingSpans {
        var current: LiveBlock.Think? = null
        var lastAct = -1
        val byCall = LinkedHashMap<String, Long>()
        val blocks = liveBlocks.value
        blocks.forEachIndexed { index, block ->
            when (block) {
                is LiveBlock.Think -> current = block
                is LiveBlock.Act -> {
                    lastAct = index
                    val thought = current ?: return@forEachIndexed
                    val callId = block.step.id
                    if (callId.isBlank() || thought.reasoning.isBlank()) return@forEachIndexed
                    val ms = thoughtMs(thought, now)
                    if (ms > 0L) byCall[callId] = ms
                }
                is LiveBlock.Say -> Unit
                is LiveBlock.Compact -> Unit
            }
        }
        val answer = blocks.drop(lastAct + 1)
            .filterIsInstance<LiveBlock.Think>()
            .lastOrNull()
            ?.takeIf { it.reasoning.isNotBlank() }
        return ThinkingSpans(
            answerMs = answer?.let { thoughtMs(it, now) } ?: 0L,
            byCall = byCall,
        )
    }

    private fun thoughtMs(thought: LiveBlock.Think, now: Long): Long {
        val end = if (thought.endedAtMs > 0L) thought.endedAtMs else now
        return (end - thought.startedAtMs).coerceAtLeast(0L)
    }

    /** Resets the thread's live state for a new turn, sent or retried. */
    private fun beginLiveTurn() {
        answerSettled = false
        errorCard.value = ""
        busy.value = true
        // The daemon has not been heard from yet for this turn. The watcher
        // starts counting from here.
        lastFrameAt = SystemClock.elapsedRealtime()
        turnHeard = false
        liveChars = 0
        liveBlocks.value = emptyList()
        liveUsage.value = null
        liveText.value = ""
        discardDeltas()
        liveReasoning.value = ""
        reasoningActive.value = false
        turnBaseSeq = -1L
        // The first request of the turn is open from the moment the question
        // leaves: this is the phone's time-to-first-token, drawn as the
        // "Sending request to provider…" block until the first word or tool.
        beginRequest()
        // The footer opens with this turn: which model answers, from when, and
        // nothing else yet. A chat that follows the agent's default says so —
        // printing whatever route happens to be current later would put another
        // turn's model next to this turn's tokens.
        // The live footer has to read like the settled one, so it names the
        // model rather than "agent default" and carries a clock. A pinned chat
        // knows its model; otherwise the daemon's default is the one that will
        // answer, and it is already in the catalogue.
        val modelLabel = selectedModel.value.ifBlank {
            providers.value.firstOrNull()?.defaultModel.orEmpty()
        }.ifBlank { "agent default" }
        turnStats.value = TurnStats(
            model = modelLabel,
            startedAtMs = SystemClock.elapsedRealtime(),
            running = true,
        )
    }

    fun send(text: String) {
        val attachments = pendingAttachments.value
        if (text.isBlank() && attachments.isEmpty()) return
        val online = conn.value == ConnState.ONLINE
        // A live turn already owns the bubble and the stream. Queueing text is
        // only meaningful while offline; sending over a healthy stream would
        // reset the answer the user is already reading.
        if (online && busy.value) {
            notice.value = "Wait for the current answer before sending another"
            return
        }
        if (!online && attachments.isNotEmpty()) {
            notice.value = "Connect to the daemon before sending attachments"
            return
        }
        if (online) beginLiveTurn()
        viewModelScope.launch {
            val wasDraft = repo.isDraft(sessionId)
            // Offline text is queued locally; a New chat is created when the
            // reconnect flush actually has a socket to send it through.
            if (online && wasDraft && !repo.ensureRemoteSession(sessionId, text.trim().take(60))) {
                clearLiveTurn()
                val msg = "Could not start this chat on the daemon. Check the connection and try again."
                errorCard.value = msg
                notice.value = msg
                return@launch
            }
            // A model chosen before the first message was kept on the phone:
            // now that the chat exists, pin it before the turn goes out.
            val pendingRoute = draftRoute
            if (online && wasDraft && pendingRoute != null) {
                runCatching { repo.setSessionModel(sessionId, pendingRoute.first, pendingRoute.second) }
                draftRoute = null
            }
            if (online) turnBaseSeq = repo.maxSeq(sessionId)
            val fresh = try {
                repo.send(sessionId, text.trim(), attachments)
            } catch (err: Exception) {
                clearLiveTurn()
                val msg = "Could not start this chat on the daemon. Check the connection and try again."
                errorCard.value = msg
                notice.value = msg
                return@launch
            }
            pendingAttachments.value = emptyList()
            if (!online) {
                notice.value = "Queued — it will send when the daemon reconnects"
            } else if (!fresh) {
                notice.value = "Already sent, waiting for reply"
            }
        }
    }

    /**
     * Resumes the unfinished turn the engine stored. No text is sent and no
     * local user row is added: the thread keeps one question, and the provider
     * sees the tool trail that already exists instead of the prompt twice.
     */
    fun retryLast() {
        if (conn.value != ConnState.ONLINE) {
            notice.value = "Not connected to the daemon"
            return
        }
        if (sessionId.isBlank()) return
        viewModelScope.launch {
            if (repo.isDraft(sessionId)) {
                errorCard.value = ""
                notice.value = "This chat has not started yet — send the message again"
                return@launch
            }
            beginLiveTurn()
            turnBaseSeq = repo.maxSeq(sessionId)
            if (!repo.retryTurn(sessionId)) {
                notice.value = "Could not ask the daemon to retry"
                clearLiveTurn()
            }
        }
    }

    /**
     * The daemon does not have this chat. D1 holds the only copy of the
     * history and the phone's rows are just its cache, so the transcript
     * cannot be continued or restored from here: drop the cached chat and open
     * a New chat in its place. Doing it on the error itself means the screen
     * never stays on a chat whose every message would fail the same way, and
     * the notice says plainly where the history went.
     */
    private fun dropDeadChat(dead: String) {
        viewModelScope.launch {
            repo.forgetDeadSession(dead)
            if (sessionId != dead) return@launch
            recoveryJob?.cancel()
            recoveryJob = null
            val next = runCatching { repo.createDraftSession() }.getOrNull()
            if (next == null) {
                notice.value = "This chat no longer exists on the daemon. Its history was lost there."
                return@launch
            }
            sessionId = next
            startWindow(next)
            selectedProvider.value = ""
            selectedModel.value = ""
            hasStoredRoute.value = false
            draftRoute = null
            settings.saveSession(next)
            notice.value = "This chat no longer exists on the daemon — its history was lost there. A new chat is open."
        }
    }

    /**
     * "New chat" opens a phone-only draft. The daemon session is created by
     * [send] with the first message, so an empty chat never leaves a row on the
     * server that a later tap can find stale.
     */
    fun newChat() {
        switchJob?.cancel()
        viewModelScope.launch {
            recoveryJob?.cancel()
            recoveryJob = null
            runCatching { repo.createDraftSession() }
                .onSuccess {
                    sessionId = it
                    startWindow(it)
                    selectedProvider.value = ""
                    selectedModel.value = ""
                    hasStoredRoute.value = false
                    draftRoute = null
                    settings.saveSession(it)
                }
                .onFailure { notice.value = "Failed to create a new chat: ${it.message}" }
        }
    }

    fun openChat(id: String) {
        if (id == sessionId) return
        switchJob?.cancel()
        switchJob = viewModelScope.launch {
            recoveryJob?.cancel()
            recoveryJob = null
            // Switch the visible window from Room before touching the network.
            // repo.selectSession used to be first and waited for a D1 refresh,
            // so the drawer closed on the old chat and the new one appeared
            // only when that round trip finished. The cached page is local and
            // can be on screen in the same frame as the tap.
            sessionId = id
            startWindow(id)
            if (sessionId != id) return@launch
            selectedProvider.value = ""
            selectedModel.value = ""
            hasStoredRoute.value = false
            draftRoute = null
            settings.saveSession(id)
            if (sessionId != id) return@launch
            // Now make it the daemon's current chat and pull the network tail;
            // openSession's remote work runs in the repository's own scope.
            repo.selectSession(id)
            if (sessionId != id) return@launch
            // The header shows this chat's model, not the daemon default.
            runCatching { repo.sessionRoute(id) }.getOrNull()?.let { (provider, model) ->
                selectedProvider.value = provider
                selectedModel.value = model
                hasStoredRoute.value = true
            }
            recoverInterruptedTurn()
        }
    }

    fun renameChat(id: String, title: String) {
        viewModelScope.launch { repo.renameSession(id, title) }
    }

    fun deleteChat(id: String) {
        switchJob?.cancel()
        viewModelScope.launch {
            repo.deleteSession(id)
            if (id == sessionId) {
                val next = repo.activeSession.value
                sessionId = next
                startWindow(next)
                selectedProvider.value = ""
                selectedModel.value = ""
                hasStoredRoute.value = false
                draftRoute = null
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
            // The drawer is drawn from the cache, so the network refresh only
            // updates those rows: the list is usable while this is in flight,
            // and stays usable when it fails.
            runCatching { repo.syncSessions() }
            runCatching { repo.refreshLatest(sessionId) }
                .onFailure { notice.value = "Failed to fetch latest: ${it.message}" }
            // A cold start that had no cache yet (or a chat whose first pull
            // failed) can open its window now that the newest rows are here.
            if (windowFloor.value == null || windowFloor.value == Long.MIN_VALUE) {
                startWindow(sessionId)
            }
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

    /**
     * Reads the newest page from Room and opens the window there. The rows
     * below it stay cached on disk; scrolling up asks for them one page at a
     * time. This is what makes reopening a long chat instant instead of
     * mapping the whole transcript before the first frame.
     */
    private suspend fun startWindow(sid: String): MessageWindow? {
        val page = try {
            repo.latestWindow(sid, CHAT_PAGE)
        } catch (_: Exception) {
            return null
        }
        if (sessionId != sid) return null
        // The cache-first pass has already opened this chat and the reader may
        // have scrolled pages in while the network refresh ran. Re-anchoring
        // now would throw those pages away and pull the view back to the tail;
        // new rows are already inside the window because it has no upper bound.
        val open = windowFloor.value
        if (open != null && open != Long.MIN_VALUE) return page
        olderExhausted = false
        loadingOlder.value = false
        hasOlder.value = page.hasOlder
        // An empty chat has no floor yet. Long.MIN_VALUE keeps the window open
        // so the optimistic row of the first send (seq 1) appears immediately.
        windowFloor.value = page.messages.firstOrNull()?.seq ?: Long.MIN_VALUE
        return page
    }

    /**
     * One page up, from the cache when it has one and from the server only
     * when it does not. Concurrent triggers are folded into one request: the
     * scroll listener can fire several times while a page is in flight.
     */
    fun loadOlder() {
        val sid = sessionId
        val floor = windowFloor.value ?: return
        if (loadingOlder.value || !hasOlder.value || olderExhausted || floor <= 1L) return
        loadingOlder.value = true
        viewModelScope.launch {
            val page = try {
                repo.loadOlderWindow(sid, floor, CHAT_PAGE)
            } catch (err: Exception) {
                notice.value = "Failed to load older history: ${err.message}"
                loadingOlder.value = false
                return@launch
            }
            if (sessionId != sid) return@launch
            if (page.messages.isNotEmpty()) {
                // The new floor is the page's oldest message, so Room maps the
                // page together with everything it already had.
                windowFloor.value = page.messages.first().seq
            }
            hasOlder.value = page.hasOlder
            olderExhausted = !page.hasOlder
            loadingOlder.value = false
        }
    }

    fun clearNotice() { notice.value = "" }

    override fun onCleared() {
        super.onCleared()
    }
}

/**
 * The rows the thread draws, from the rows the repository has.
 *
 * A turn is written to storage as it happens: the narration before a tool round
 * and every tool step land in the cache while the live timeline is still the
 * thing showing them. Drawing both put each of those items on screen twice, and
 * the second copy sat under the answer, so a turn with three tool rounds looked
 * like six. [baseSeq] is the seq the thread ended at when the turn was sent, and
 * -1 between turns; while it is a real number the rows above it belong to the
 * live timeline and are withheld here.
 *
 * The question stays whatever happens. The daemon stores it before it answers,
 * so a thread that hid everything above the base would empty itself the moment
 * the reader pressed send — and a question the app cannot see is a question it
 * cannot send a second time to.
 */
internal fun threadRows(rows: List<ChatMessage>, baseSeq: Long): List<ChatMessage> =
    if (baseSeq < 0L) rows else rows.filter { it.seq <= baseSeq || it.role == "user" }

/**
 * One LazyColumn key per live block, in the list's own order.
 *
 * A repeated key is not a visual glitch: a lazy list throws "Key was already
 * used" and the app dies in the middle of a turn. Keying the streaming items by
 * a millisecond stamp invited exactly that — a tool flush and an engine text
 * block can land in the same millisecond, and so can two pieces of narration.
 * Where the engine named the block, its id is the key, because it names the one
 * thing this item is and survives the list changing around it; a block with no
 * id of its own keeps its stamp, and only a second block wearing the same stamp
 * is numbered, so nothing is dropped or shared.
 */
internal fun uniqueLiveKeys(blocks: List<ChatViewModel.LiveBlock>): List<String> {
    val used = HashSet<String>(blocks.size)
    return blocks.map { block ->
        val base = when (block) {
            is ChatViewModel.LiveBlock.Say -> "live-say-" + block.id.ifBlank { block.atMs.toString() }
            is ChatViewModel.LiveBlock.Act -> "live-act-" + block.step.key
            is ChatViewModel.LiveBlock.Think -> "live-think-" + block.id.ifBlank { block.startedAtMs.toString() }
            is ChatViewModel.LiveBlock.Compact -> "live-compact-" + block.atMs
        }
        if (used.add(base)) return@map base
        var n = 2
        while (!used.add("$base#$n")) n++
        "$base#$n"
    }
}
