package com.tulipskun.aixodia.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// Mirrors ai sdk/io.go + sdk/types.go + sdk/trace.go (additive-only), plus the
// AXCH-005 fields the mock agent adds: which agent spoke (main/sub), which job
// a step belongs to, and the client id used to clear the pending marker.

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
    @Json(name = "free_only") val freeOnly: Boolean = false,
    @Json(name = "key_count") val keyCount: Int = 0,
    @Json(name = "model_count") val modelCount: Int = 0,
    @Json(name = "reachable") val reachable: Boolean = false,
    @Json(name = "probed") val probed: Boolean = false,
    @Json(name = "working_model") val workingModel: String = "",
    @Json(name = "last_error") val lastError: String = "",
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
    @Json(name = "sub") val sub: AgentSettings = AgentSettings(),
    @Json(name = "sub_enabled") val subEnabled: Boolean = true,
)

/** One tool step of the turn being streamed: call, then its result. */
data class ToolStep(
    val name: String,
    val args: String = "",
    val result: String = "",
    val isError: Boolean = false,
    val done: Boolean = false,
    val durationMs: Long = 0,
)

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
    @Json(name = "agent") val agent: String = "", // main | sub | worker | system
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
    // Footer of this one message (AX-095): the model that produced it and how
    // long it took, straight off the terminal trace.
    @Json(name = "model") val model: String = "",
    @Json(name = "duration_ms") val durationMs: Long = 0,
)

// Canonical outbound frame (ai Input).
@JsonClass(generateAdapter = true)
data class AiInput(
    @Json(name = "type") val type: String = "message",
    @Json(name = "source") val source: String = "mobile",
    @Json(name = "session_id") val sessionId: String = "",
    @Json(name = "role") val role: String = "user",
    @Json(name = "content") val content: List<ContentPart> = emptyList(),
    // A cancel that names a job stops that one sub agent; without it the
    // cancel stops the whole turn.
    @Json(name = "job_id") val jobId: String = "",
    @Json(name = "client_msg_id") val clientMsgId: String = "",
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
    val agent: String = "", // main | sub | worker | system
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

data class ChatSession(
    val id: String,
    val title: String,
    val model: String = "",
    val unread: Int = 0,
    val lastSnippet: String = "",
    val lastAt: Long = 0,
)

@JsonClass(generateAdapter = true)
data class SessionAgentConfig(
    @Json(name = "provider") val provider: String = "",
    @Json(name = "model") val model: String = "",
    @Json(name = "pinned") val pinned: Boolean = false,
    @Json(name = "sub_provider") val subProvider: String = "",
    @Json(name = "sub_model") val subModel: String = "",
    @Json(name = "sub_enabled") val subEnabled: Boolean = true,
    @Json(name = "sub_pinned") val subPinned: Boolean = false,
    // What this chat actually runs on, and whether that came from the chat or
    // from the agent defaults, so the sheet can say which it is showing.
    @Json(name = "generation") val generation: GenerationSettings = GenerationSettings(),
    @Json(name = "generation_source") val generationSource: String = "",
)
