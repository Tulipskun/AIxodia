package com.tulipskun.aixodia.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// Mirrors ai sdk/io.go + sdk/types.go + sdk/trace.go (additive-only).

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

// Canonical inbound display frame (ai Output).
@JsonClass(generateAdapter = true)
data class AiOutput(
    @Json(name = "source") val source: String = "",
    @Json(name = "session_id") val sessionId: String = "",
    @Json(name = "content") val content: List<ContentPart> = emptyList(),
    @Json(name = "text") val text: String = "",
    @Json(name = "stage") val stage: String = "",
    @Json(name = "kind") val kind: String = "message", // message | trace | error | done
    @Json(name = "seq") val seq: Long = 0,
    @Json(name = "role") val role: String = "model",
    @Json(name = "tool_call") val toolCall: ToolCall? = null,
    @Json(name = "usage") val usage: Usage? = null,
)

// Canonical outbound frame (ai Input).
@JsonClass(generateAdapter = true)
data class AiInput(
    @Json(name = "type") val type: String = "message",
    @Json(name = "source") val source: String = "mobile",
    @Json(name = "session_id") val sessionId: String = "",
    @Json(name = "role") val role: String = "user",
    @Json(name = "content") val content: List<ContentPart> = emptyList(),
    @Json(name = "token") val token: String = "",
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
    val role: String, // user | model | status
    val text: String,
    val createdAt: Long,
    val pending: Boolean = false,
    val streaming: Boolean = false,
    val toolNote: String? = null,
)

data class ChatSession(
    val id: String,
    val title: String,
    val model: String = "",
    val unread: Int = 0,
    val lastSnippet: String = "",
    val lastAt: Long = 0,
)
