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
        ToolRowView(text = content, toolName = name, toolArgs = humanToolArgs(map["arguments"] as? String ?: ""))
    } else {
        ToolRowView(text = content, toolName = name, toolArgs = "")
    }
}

/**
 * The one thing the tool was asked to do, for the activity line: the command
 * for bash, the path for read, the URL for fetch. Anything without a known
 * key falls back to the compacted raw arguments so nothing is lost.
 */
fun humanToolArgs(rawArgs: String): String {
    val clean = rawArgs.trim()
    if (clean.isEmpty()) return ""
    val map = runCatching { mapAdapter.fromJson(clean) as? Map<*, *> }.getOrNull()
        ?: return clean.replace(Regex("\\s+"), " ").take(120)
    for (key in listOf("command", "filePath", "path", "url", "timezone", "text", "input", "query")) {
        (map[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.take(120) }
    }
    return clean.replace(Regex("\\s+"), " ").take(120)
}

/**
 * Folds each tool_call row with the tool_result that answers it into a single
 * block — one tool activity like Claude shows, not two raw bubbles. A call
 * with no result yet (still running) and a result whose call sits outside the
 * loaded page pass through unchanged.
 */
fun mergeToolPairs(messages: List<ChatMessage>): List<ChatMessage> {
    if (messages.isEmpty()) return messages
    val out = ArrayList<ChatMessage>(messages.size)
    var i = 0
    while (i < messages.size) {
        val m = messages[i]
        val n = messages.getOrNull(i + 1)
        if (m.role == "tool_call" && n != null && n.role == "tool_result") {
            out += n.copy(
                toolName = m.toolName.ifBlank { n.toolName },
                toolArgs = m.toolArgs,
                model = n.model.ifBlank { m.model },
            )
            i += 2
        } else {
            out += m
            i += 1
        }
    }
    return out
}
