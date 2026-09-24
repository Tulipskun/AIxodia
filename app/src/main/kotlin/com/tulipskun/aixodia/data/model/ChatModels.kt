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
)

@JsonClass(generateAdapter = true)
data class ModelsPage(@Json(name = "providers") val providers: List<ProviderView> = emptyList())

/** One tool step of the turn being streamed: call, then its result. */
data class ToolStep(
    val name: String,
    val args: String = "",
    val result: String = "",
    val isError: Boolean = false,
    val done: Boolean = false,
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
)

// Canonical outbound frame (ai Input).
@JsonClass(generateAdapter = true)
data class AiInput(
    @Json(name = "type") val type: String = "message",
    @Json(name = "source") val source: String = "mobile",
    @Json(name = "session_id") val sessionId: String = "",
    @Json(name = "role") val role: String = "user",
    @Json(name = "content") val content: List<ContentPart> = emptyList(),
    @Json(name = "client_msg_id") val clientMsgId: String = "",
)

@JsonClass(generateAdapter = true)
data class Usage(
    @Json(name = "input_tokens") val inputTokens: Int = 0,
    @Json(name = "output_tokens") val outputTokens: Int = 0,
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
)

data class ChatSession(
    val id: String,
    val title: String,
    val model: String = "",
    val unread: Int = 0,
    val lastSnippet: String = "",
    val lastAt: Long = 0,
)
