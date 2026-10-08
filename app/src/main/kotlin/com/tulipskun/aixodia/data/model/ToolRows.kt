package com.tulipskun.aixodia.data.model

import com.squareup.moshi.Moshi

/**
 * The daemon stores each tool step as JSON in the turn's text (role tool_call or
 * tool_result). This splits that JSON back into what the chat shows: the body,
 * the tool name and the arguments. Any other row, or JSON we cannot read, is
 * shown unchanged so nothing is lost.
 */
data class ToolRowView(val text: String, val toolName: String, val toolArgs: String)

private val mapAdapter by lazy { Moshi.Builder().build().adapter(Map::class.java) }

fun toolRowView(role: String, raw: String): ToolRowView {
    if (role != "tool_call" && role != "tool_result") return ToolRowView(raw, "", "")
    val map = runCatching { mapAdapter.fromJson(raw) as? Map<*, *> }.getOrNull()
        ?: return ToolRowView(raw, "", "")
    val name = map["name"] as? String ?: ""
    val content = map["content"] as? String ?: ""
    return if (role == "tool_call") {
        ToolRowView(text = content, toolName = name, toolArgs = map["arguments"] as? String ?: "")
    } else {
        ToolRowView(text = content, toolName = name, toolArgs = "")
    }
}
