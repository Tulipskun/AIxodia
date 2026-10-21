package com.tulipskun.aixodia.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.tulipskun.aixodia.data.remote.TurnRow

// Mirrors ai sdk/io.go + sdk/types.go + sdk/trace.go (additive-only), plus the
// fields the agent adds: which agent spoke, which job a step belongs to, and
// the client id used to clear the pending marker.

@JsonClass(generateAdapter = true)
data class ContentPart(
    @Json(name = "type") val type: String = "text",
    @Json(name = "text") val text: String = "",
)

@JsonClass(generateAdapter = true)
data class ToolCall(
    @Json(name = "id") val id: String = "",
    @Json(name = "name") val name: String = "",
    @Json(name = "arguments") val arguments: String = "",
)

@JsonClass(generateAdapter = true)
data class ToolResultView(
    @Json(name = "id") val id: String = "",
    @Json(name = "name") val name: String = "",
    @Json(name = "text") val text: String = "",
    @Json(name = "is_error") val isError: Boolean = false,
)

/** One provider with the models the daemon can actually route to right now. */
@JsonClass(generateAdapter = true)
data class ProviderView(
    @Json(name = "id") val id: String = "",
    @Json(name = "name") val name: String = "",
    @Json(name = "default_model") val defaultModel: String = "",
    @Json(name = "models") val models: List<ModelView> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class ModelView(
    @Json(name = "id") val id: String = "",
    @Json(name = "name") val name: String = "",
    @Json(name = "supports_streaming") val supportsStreaming: Boolean = false,
    @Json(name = "supports_tools") val supportsTools: Boolean = false,
    // What this particular model accepts, so a setting it would refuse is never
    // offered in the first place. The daemon discovered this and used to report
    // only two of it.
    @Json(name = "supports_temperature") val supportsTemperature: Boolean = false,
    @Json(name = "supports_thinking") val supportsThinking: Boolean = false,
    // The thinking levels this model actually accepts, as the daemon reports
    // them. Not a fixed list here on purpose: the allowed set is the provider's
    // answer, so the picker offers exactly those and nothing invented.
    @Json(name = "thinking_levels") val thinkingLevels: List<String> = emptyList(),
    @Json(name = "supports_top_p") val supportsTopP: Boolean = false,
    @Json(name = "supports_top_k") val supportsTopK: Boolean = false,
    @Json(name = "supports_stop_sequences") val supportsStopSequences: Boolean = false,
    @Json(name = "supports_presence_penalty") val supportsPresencePenalty: Boolean = false,
    @Json(name = "supports_frequency_penalty") val supportsFrequencyPenalty: Boolean = false,
    @Json(name = "supports_seed") val supportsSeed: Boolean = false,
)

/**
 * One generation knob, with "not set" told apart from zero: a null value is left
 * for the provider to decide, and a value is one the model was asked to obey.
 */
@JsonClass(generateAdapter = true)
data class GenerationSettings(
    @Json(name = "thinking_level") val thinkingLevel: String = "",
    @Json(name = "temperature") val temperature: Double? = null,
    @Json(name = "top_p") val topP: Double? = null,
    @Json(name = "top_k") val topK: Double? = null,
    @Json(name = "stop_sequences") val stopSequences: List<String>? = null,
    @Json(name = "presence_penalty") val presencePenalty: Double? = null,
    @Json(name = "frequency_penalty") val frequencyPenalty: Double? = null,
    @Json(name = "seed") val seed: Long? = null,
    @Json(name = "max_output_tokens") val maxOutputTokens: Int = 0,
) {
    /**
     * True when the daemon is reporting values, rather than nothing stored. A
     * stop sequence of blanks is nothing stored: the card filters them out before
     * saving, and a list of them means the same as no list at all.
     */
    val isSet: Boolean
        get() = thinkingLevel.isNotBlank() || temperature != null || topP != null || topK != null ||
            stopSequences.orEmpty().any { it.isNotBlank() } || presencePenalty != null ||
            frequencyPenalty != null || seed != null || maxOutputTokens != 0

    /** The thinking levels worth showing, which the four providers all accept. */
    companion object {
        val thinkingLevels = listOf("", "low", "medium", "high")
    }
}

@JsonClass(generateAdapter = true)
data class ModelsPage(@Json(name = "providers") val providers: List<ProviderView> = emptyList())

/** One configured provider as the daemon reports it. Keys are never sent back. */
@JsonClass(generateAdapter = true)
data class ProviderStatus(
    @Json(name = "id") val id: String = "",
    @Json(name = "adapter") val adapter: String = "",
    @Json(name = "endpoint") val endpoint: String = "",
    @Json(name = "key_count") val keyCount: Int = 0,
    @Json(name = "model_count") val modelCount: Int = 0,
    @Json(name = "reachable") val reachable: Boolean = false,
    @Json(name = "probed") val probed: Boolean = false,
    @Json(name = "working_model") val workingModel: String = "",
    @Json(name = "last_error") val lastError: String = "",
    // Built-in providers are carried by the engine (the opencode free tier
    // needs no key) and cannot be added, edited or removed.
    @Json(name = "builtin") val builtin: Boolean = false,
)

@JsonClass(generateAdapter = true)
data class ProvidersPage(@Json(name = "providers") val providers: List<ProviderStatus> = emptyList())

@JsonClass(generateAdapter = true)
data class AgentRoute(
    @Json(name = "provider") val provider: String = "",
    @Json(name = "model") val model: String = "",
)

@JsonClass(generateAdapter = true)
data class AgentSettings(
    @Json(name = "provider") val provider: String = "",
    @Json(name = "model") val model: String = "",
    @Json(name = "generation") val generation: GenerationSettings = GenerationSettings(),
)

@JsonClass(generateAdapter = true)
data class SettingsView(
    @Json(name = "main") val main: AgentSettings = AgentSettings(),
)

/** One tool step of the turn being streamed: call, then its result. */
data class ToolStep(
    /**
     * The call id the daemon named. One turn runs the same tool several times,
     * so the name alone cannot tell two steps apart and must not be the key.
     */
    val id: String = "",
    val name: String,
    val args: String = "",
    val result: String = "",
    val isError: Boolean = false,
    val done: Boolean = false,
    val durationMs: Long = 0,
    /** When the call started, so a step still running can show its own elapsed time. */
    val startedAtMs: Long = 0,
) {
    /** What makes one step of a tool distinct from the next run of that tool. */
    val key: String get() = id.ifBlank { name }
}

/**
 * One display unit as the engine describes it. The engine owns this vocabulary
 * — text, reasoning, tool, request, status — and the thread renders each type
 * with one generic renderer, so changing how a turn looks means changing what
 * the engine emits, not shipping a new app. A type this app has never seen
 * still draws as a plain card with its title and body.
 */
@JsonClass(generateAdapter = true)
data class Block(
    @Json(name = "id") val id: String = "",
    @Json(name = "type") val type: String = "",
    @Json(name = "state") val state: String = "",
    @Json(name = "text") val text: String = "",
    @Json(name = "name") val name: String = "",
    @Json(name = "arguments") val args: String = "",
    @Json(name = "result") val result: String = "",
    @Json(name = "is_error") val isError: Boolean = false,
    @Json(name = "duration_ms") val durationMs: Long = 0,
    @Json(name = "thinking_ms") val thinkingMs: Long = 0,
    @Json(name = "round") val round: Int = 0,
) {
    companion object {
        const val TEXT = "text"
        const val REASONING = "reasoning"
        const val TOOL = "tool"
        const val REQUEST = "request"
        const val STATUS = "status"

        /**
         * The block id of one provider request round. The engine names it
         * "req<N>"; a tool event carries only its round, so the phone
         * derives the id the same way to settle the round that spoke.
         */
        fun requestId(round: Int): String = "req$round"

        /** Decodes a stored block; null for rows that predate blocks. */
        fun parse(raw: String): Block? {
            if (raw.isBlank()) return null
            return runCatching {
                val o = org.json.JSONObject(raw)
                val type = o.optString("type")
                if (type.isBlank()) return null
                Block(
                    id = o.optString("id"),
                    type = type,
                    state = o.optString("state"),
                    text = o.optString("text"),
                    name = o.optString("name"),
                    args = o.optString("arguments"),
                    result = o.optString("result"),
                    isError = o.optBoolean("is_error"),
                    durationMs = o.optLong("duration_ms"),
                    thinkingMs = o.optLong("thinking_ms"),
                    round = o.optInt("round"),
                )
            }.getOrNull()
        }
    }

    /** True for a type with its own drawing; the rest get the plain card. */
    val known: Boolean get() = type == TEXT || type == REASONING || type == TOOL ||
        type == REQUEST || type == STATUS
}

// Canonical inbound display frame (ai Output + agent attribution).
@JsonClass(generateAdapter = true)
data class AiOutput(
    @Json(name = "source") val source: String = "",
    @Json(name = "session_id") val sessionId: String = "",
    @Json(name = "content") val content: List<ContentPart> = emptyList(),
    @Json(name = "text") val text: String = "",
    @Json(name = "stage") val stage: String = "",
    @Json(name = "kind") val kind: String = "message", // ack | delta | message | trace | done | error
    @Json(name = "seq") val seq: Long = 0,
    @Json(name = "role") val role: String = "model",
    @Json(name = "agent") val agent: String = "", // main | worker | system
    @Json(name = "job_id") val jobId: String = "",
    @Json(name = "client_msg_id") val clientMsgId: String = "",
    @Json(name = "tool_call") val toolCall: ToolCall? = null,
    @Json(name = "tool_result") val toolResult: ToolResultView? = null,
    @Json(name = "usage") val usage: Usage? = null,
    @Json(name = "input_tokens") val inputTokens: Int = 0,
    @Json(name = "output_tokens") val outputTokens: Int = 0,
    @Json(name = "cache_read_tokens") val cacheRead: Int = 0,
    @Json(name = "cache_write_tokens") val cacheWrite: Int = 0,
    @Json(name = "reasoning_ms") val reasoningMs: Long = 0,
    // ReasoningTokens is output the model spent thinking before answering, and
    // InputIncludesCache says whether InputTokens already contains the cache
    // parts. OpenAI and Gemini report the prompt total with cache inside it,
    // Anthropic reports cache beside it; without this flag the two conventions
    // are indistinguishable and a cache count looks bigger than the input it
    // belongs to.
    @Json(name = "reasoning_tokens") val reasoningTokens: Int = 0,
    @Json(name = "input_includes_cache") val inputIncludesCache: Boolean = false,
    @Json(name = "tool_duration_ms") val toolDurationMs: Long = 0,
    // Footer of this one message: the model that produced it and how
    // long it took, straight off the terminal trace.
    @Json(name = "model") val model: String = "",
    @Json(name = "duration_ms") val durationMs: Long = 0,
    @Json(name = "generation_ms") val generationMs: Long = 0,
    @Json(name = "reasoning_content") val reasoningContent: String = "",
    // The display block for this frame, described by the engine. Frames from
    // an older daemon carry nothing here and take the stage-based path.
    @Json(name = "block") val block: Block? = null,
    @Json(name = "turns") val turns: List<TurnRow> = emptyList(),
    @Json(name = "turns_synced") val turnsSynced: Boolean = false,
)

// Canonical outbound frame (ai Input).
@JsonClass(generateAdapter = true)
data class AiInput(
    @Json(name = "type") val type: String = "message",
    @Json(name = "source") val source: String = "mobile",
    @Json(name = "session_id") val sessionId: String = "",
    @Json(name = "role") val role: String = "user",
    @Json(name = "content") val content: List<ContentPart> = emptyList(),
    // A cancel without a job id stops the whole turn.
    @Json(name = "job_id") val jobId: String = "",
    @Json(name = "client_msg_id") val clientMsgId: String = "",
    // Files the phone already uploaded; the daemon hands their paths to the agent.
    @Json(name = "attachments") val attachments: List<AttachmentRef> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class Usage(
    @Json(name = "input_tokens") val inputTokens: Int = 0,
    @Json(name = "output_tokens") val outputTokens: Int = 0,
    @Json(name = "total_tokens") val totalTokens: Int = 0,
    @Json(name = "cache_read_tokens") val cacheRead: Int = 0,
    @Json(name = "cache_write_tokens") val cacheWrite: Int = 0,
    @Json(name = "reasoning_tokens") val reasoningTokens: Int = 0,
    // D1 hands this back as 0/1 from an INTEGER column.
    @Json(name = "input_includes_cache") val inputIncludesCache: Boolean = false,
)

data class ChatMessage(
    val id: String,
    val sessionId: String,
    val seq: Long,
    val role: String, // user | model | tool_call | tool_result | system
    val text: String,
    val createdAt: Long,
    val pending: Boolean = false,
    val agent: String = "", // main | worker | system
    val jobId: String = "",
    val stage: String = "",
    val toolName: String = "",
    val toolArgs: String = "",
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
    val cacheRead: Int = 0,
    val cacheWrite: Int = 0,
    val reasoningTokens: Int = 0,
    val inputIncludesCache: Boolean = false,
    val model: String = "",
    val durationMs: Long = 0,
    /** Time inside the provider only. Zero when the turn predates this split. */
    val generationMs: Long = 0,
    /**
     * How long the turn spent thinking before its first word, measured on the
     * phone from send to first token. The daemon never reports this — its D1
     * rows carry no thinking duration — so it is recorded locally when the
     * turn runs and kept across mirrors. Zero when the turn predates it.
     */
    val thinkingMs: Long = 0,
    /**
     * What a reasoning model wrote before its answer, stored by the daemon
     * with the turn. Blank for models that do not think, and for turns that
     * predate the column.
     */
    val reasoningContent: String = "",
    /**
     * The row's display block as JSON, written by the engine. Blank on rows
     * that predate blocks, which render through the role instead.
     */
    val block: String = "",
)

/**
 * The prompt split by the convention the provider used. `fresh` is the part that
 * was not served from cache and `total` is the whole prompt, so a reader can
 * compare the two without guessing which one contains the other.
 */
fun ChatMessage.freshInputTokens(): Int =
    if (inputIncludesCache) (tokensIn - cacheRead).coerceAtLeast(0) else tokensIn

fun ChatMessage.totalInputTokens(): Int =
    if (inputIncludesCache) tokensIn else tokensIn + cacheRead + cacheWrite

/**
 * True for the model's own words, as opposed to a question or a tool step.
 *
 * The role is spelled twice in the wild: this app writes "model" for a reply it
 * makes itself, and the daemon's D1 rows say "assistant" — the name the provider
 * APIs use. Both are answers, so the test is what a message is *not* rather than
 * a list of the spellings it might be.
 */
val ChatMessage.isAnswer: Boolean
    get() = role != "user" && !role.startsWith("tool")

/**
 * The role a row is stored under.
 *
 * The daemon names the model's rows "assistant", the name the provider APIs use,
 * while this app's own rows say "model". They are folded into one word on the way
 * in so the table has a single vocabulary; a row written before that fold is read
 * through [isAnswer], which takes either spelling.
 */
fun normalizedRole(role: String): String = when (role) {
    "", "assistant" -> "model"
    else -> role
}

data class ChatSession(
    val id: String,
    val title: String,
    val model: String = "",
    val unread: Int = 0,
    val lastSnippet: String = "",
    val lastAt: Long = 0,
)

/**
 * A whole chat's usage, summed by SQLite. It carries the same figures the
 * session strip used to add up from every loaded message, so the strip can stay
 * complete while the thread itself only holds a page of rows.
 *
 * [lastTurnMs] is the newest answer that reported a duration, not the longest:
 * it is the "last turn" reading, not an average.
 */
data class StoredTotals(
    val promptTokens: Int = 0,
    val cacheRead: Int = 0,
    val freshInput: Int = 0,
    val outTokens: Int = 0,
    val lastTurnMs: Long = 0,
    val totalMs: Long = 0,
)
