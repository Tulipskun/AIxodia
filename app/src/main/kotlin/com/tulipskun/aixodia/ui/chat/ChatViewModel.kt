package com.tulipskun.aixodia.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.os.SystemClock
import com.tulipskun.aixodia.SettingsStore
import com.tulipskun.aixodia.data.model.AiOutput
import com.tulipskun.aixodia.data.model.AttachmentRef
import com.tulipskun.aixodia.data.model.Block
import com.tulipskun.aixodia.data.model.ProviderStatus
import com.tulipskun.aixodia.data.model.ProviderView
import com.tulipskun.aixodia.data.model.ToolStep
import com.tulipskun.aixodia.data.remote.ConnState
import com.tulipskun.aixodia.data.repo.ChatRepository
import com.tulipskun.aixodia.ui.display.ThinkPhase
import com.tulipskun.aixodia.ui.display.TokenField
import com.tulipskun.aixodia.ui.display.toolStepFor
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
    /** Which agent that thinking event belongs to. */
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
    /**
     * Where the thread ended when the turn was sent, so the thinking time it
     * measured can be stamped on just the rows it created. -1 when no turn is
     * in flight.
     */
    private var turnBaseSeq = -1L
    /** Send-to-first-token of the running turn, stamped onto its rows at close. */
    private var pendingThinkingMs = 0L
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
                notice.value = "Cannot connect: ${err.message ?: err::class.simpleName}"
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
                        liveReasoning.value = ""
                        reasoningActive.value = false
                        turnBaseSeq = -1L
                        pendingThinkingMs = 0L
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
        reasoningActive.value = false
        if (agent.isNotEmpty() && agent != "main") {
            status.value = "${if (agent == "sub") "sub" else agent}: " + (liveSubText + chunk).take(80)
            liveSubText += chunk
            noteSubAgent(frame, chunk.take(80))
            return
        }
        liveAgent.value = agent.ifEmpty { "main" }
        // The request has spoken: it is no longer waiting or thinking.
        settleOpenRequest()
        liveText.value += chunk
        liveChars += chunk.length
        // The clock for the rate starts here, not when the question was sent.
        val stats = turnStats.value
        if (stats.firstTokenAtMs == 0L) {
            val now = SystemClock.elapsedRealtime()
            turnStats.value = stats.copy(firstTokenAtMs = now)
            // The thinking ends where the first word begins: send to first
            // token is the figure the stored pill shows. The daemon reports no
            // thinking duration of its own, so this local measurement is the
            // only one there is.
            pendingThinkingMs = (now - stats.startedAtMs).coerceAtLeast(0L)
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
        if (agent.isNotEmpty() && agent != "main") {
            status.value = "${if (agent == "sub") "sub" else agent}: " + text.take(80)
            liveSubText = text
            noteSubAgent(frame, text.take(80))
            return
        }
        recordUsage(frame)
        if (pendingThinkingMs == 0L && turnStats.value.startedAtMs > 0L) {
            pendingThinkingMs = (SystemClock.elapsedRealtime() - turnStats.value.startedAtMs).coerceAtLeast(0L)
        }
        if (text.isBlank()) return
        // Replace whatever streamed in with the daemon's own copy: it is the one
        // that also lands in D1.
        answerSettled = true
        // A turn that never streamed still answered: close the open request so
        // its wait does not outlive the answer.
        settleOpenRequest()
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
                    liveBlocks.value = liveBlocks.value +
                        LiveBlock.Say(block.text, System.currentTimeMillis())
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
        // A turn that opens with tools never streams a word first, so
        // the first call is where its thinking ends.
        if (pendingThinkingMs == 0L) {
            val started = turnStats.value.startedAtMs
            if (started > 0L) {
                pendingThinkingMs = (now - started).coerceAtLeast(0L)
            }
        }
        // What came before this tool is its own turn, now and after.
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
                        args = block.args.ifBlank { known },
                        result = block.result.take(160),
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
    }

    private fun onTrace(f: AiOutput) {
        if (f.agent.isNotEmpty() && f.agent != "main") {
            val detail = when (f.stage) {
                "tool_call", "tool_running" -> "Using ${f.toolCall?.name ?: "tool"}"
                "tool_result" -> if (f.toolResult?.isError == true) "Tool failed" else "Tool done"
                "request" -> "Sending request"
                "response_text" -> "Thinking"
                "retry_wait" -> "Retrying"
                else -> f.text.ifEmpty { f.stage }
            }
            noteSubAgent(f, detail)
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
                // A turn that opens with tools never streams a word first, so
                // the first call is where its thinking ends.
                if (pendingThinkingMs == 0L) {
                    val started = turnStats.value.startedAtMs
                    if (started > 0L) {
                        pendingThinkingMs = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
                    }
                }
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
                status.value = if (f.toolResult?.isError == true) "$name failed" else "$name done"
                traceNote(status.value)
                // The result goes back to the provider and the next request
                // starts: a new "Sending request to provider…" for the next
                // round, so each request's wait and thought is its own.
                beginRequest()
            }
            "response_text" -> {
                liveThinkingMs.value = f.reasoningMs
                liveThinkingAgent.value = f.agent.ifEmpty { "main" }
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

    private fun onTurnDone(frame: AiOutput) {
        val stage = frame.stage
        // A cancel that named a job answers for that job only: the turn keeps
        // running, so the busy state and the stream stay as they are.
        if (stage.startsWith("subagent_")) {
            onSubAgentStopped(frame)
            return
        }
        if (stage == "cancelled") {
            notice.value = "Stopped"
        } else if (stage == "already_done") {
            notice.value = "That task already finished"
        }
        // The thinking was measured while the turn ran; its rows only just
        // arrived, so stamp the figure now while their seqs are known.
        val sid = frame.sessionId.ifBlank { sessionId }
        val baseSeq = turnBaseSeq
        val thinkingMs = pendingThinkingMs
        clearLiveTurn()
        answerSettled = false
        // Repository has already committed the WS rows. A D1 read is only a
        // compatibility/recovery fallback for a daemon without a complete batch.
        viewModelScope.launch {
            if (!frame.turnsSynced) runCatching { repo.refreshLatest(sid) }
            if (baseSeq >= 0L) runCatching { repo.stampThinkingMs(sid, baseSeq, thinkingMs) }
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
        liveSubText = ""
        liveBlocks.value = emptyList()
        liveThinkingMs.value = 0L
        liveReasoning.value = ""
        reasoningActive.value = false
        busy.value = false
        subAgents.value = subAgents.value.map { it.copy(running = false) }
        finishStats()
        turnBaseSeq = -1L
        pendingThinkingMs = 0L
        liveText.value = ""
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
                status.value = "Stopping sub agent…"
            }
            "subagent_not_found" -> {
                subAgents.value = rows.filterNot { it.jobId == jobId }
                notice.value = "No sub agent to stop — may have finished"
            }
            "subagent_stop_failed" -> {
                subAgents.value = rows.map { if (it.jobId == jobId) it.copy(stopping = false) else it }
                notice.value = "Failed to stop sub agent"
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
                            "stopped" -> "Stopped"
                            "failed" -> "Failed"
                            else -> "Done"
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
            notice.value = "Cannot send stop to sub agent — socket offline"
            return
        }
        subAgents.value = subAgents.value.map { if (it.jobId == jobId) it.copy(stopping = true) else it }
        status.value = "Stopping sub agent…"
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

    fun send(text: String) {
        if (conn.value != ConnState.ONLINE) {
            notice.value = "Not connected to the daemon"
            return
        }
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
        liveText.value = ""
        liveReasoning.value = ""
        reasoningActive.value = false
        pendingThinkingMs = 0L
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
        viewModelScope.launch {
            turnBaseSeq = repo.maxSeq(sessionId)
            val fresh = repo.send(sessionId, text.trim(), attachments)
            pendingAttachments.value = emptyList()
            if (!fresh) notice.value = "Already sent, waiting for reply"
        }
    }

    /** Re-sends the last message after a failure, reusing the same text. */
    fun retryLast() {
        if (lastSentText.isBlank() && lastAttachments.isEmpty()) return
        pendingAttachments.value = lastAttachments
        send(lastSentText)
    }

    /** "New chat" — the only session action that needs no argument. */
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
                .onFailure { notice.value = "Failed to create a new chat: ${it.message}" }
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
                .onFailure { notice.value = "Failed to fetch latest: ${it.message}" }
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
                .onFailure { notice.value = "Failed to load older history: ${it.message}" }
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
                    "Enable sub agent using the agent default"
                } else {
                    "Disable sub agent for this chat"
                }
            } else {
                notice.value = "Failed to save sub agent"
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
                notice.value = "Sub agent now uses the agent default"
            } else {
                notice.value = "Failed to clear sub agent"
            }
        }
    }

    fun chooseSubProvider(id: String) { subAgentProvider.value = id }
    fun chooseSubModel(id: String) { subAgentModel.value = id }
    override fun onCleared() {
        super.onCleared()
    }
}
