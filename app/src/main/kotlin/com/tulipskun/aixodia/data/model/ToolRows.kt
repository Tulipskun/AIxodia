package com.tulipskun.aixodia.data.model

import com.squareup.moshi.Moshi

/**
 * The daemon stores each tool step as JSON in the turn's text (role tool_call or
 * tool_result). This splits that JSON back into what the chat shows: the body,
 * the tool name and the arguments. Any other row, or JSON we cannot read, is
 * shown unchanged so nothing is lost.
 */
data class ToolRowView(
    val text: String,
    val toolName: String,
    val toolArgs: String,
    /**
     * What the model thought before this call, as the daemon stored it inside
     * the call row's JSON. It belongs to this one request, not the turn's final
     * answer, so it is carried through the merge and shown above the card.
     */
    val reasoning: String = "",
)

private val mapAdapter by lazy { Moshi.Builder().build().adapter(Map::class.java) }

fun toolRowView(role: String, raw: String): ToolRowView {
    if (role != "tool_call" && role != "tool_result") return ToolRowView(raw, "", "")
    val map = runCatching { mapAdapter.fromJson(raw) as? Map<*, *> }.getOrNull()
        ?: return ToolRowView(raw, "", "")
    val name = map["name"] as? String ?: ""
    val content = map["content"] as? String ?: ""
    return if (role == "tool_call") {
        ToolRowView(
            text = content,
            toolName = name,
            toolArgs = humanToolArgs(map["arguments"] as? String ?: ""),
            reasoning = map["reasoning"] as? String ?: "",
        )
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
    // Where the engine stored a block, its id is the call id both sides carry,
    // so each result names the exact call it answers — the only safe pairing
    // once a tool ran several times in one round. Rows without blocks (an
    // older engine) fall back to position: each result answers the earliest
    // call still waiting. A result whose call sits outside the loaded page
    // passes through untouched either way.
    data class Waiting(val index: Int, val id: String)
    val out = ArrayList<ChatMessage>(messages.size)
    val waiting = ArrayList<Waiting>()
    for (m in messages) {
        when {
            m.role == "tool_call" -> {
                out += m
                waiting += Waiting(out.lastIndex, blockCallId(m.block))
            }
            m.role == "tool_result" -> {
                val id = blockCallId(m.block)
                val w = waiting.firstOrNull { it.id.isNotBlank() && it.id == id }
                    ?: waiting.firstOrNull { it.id.isBlank() }
                if (w != null) {
                    waiting.remove(w)
                    val call = out[w.index]
                    // Mid-round prose travels on the first call row; keep it
                    // above the output instead of dropping either side. The
                    // result text itself is never touched.
                    val prose = call.text.trim()
                    val body = if (prose.isNotEmpty()) prose + "\n\n" + m.text else m.text
                    out[w.index] = m.copy(
                        toolName = call.toolName.ifBlank { m.toolName },
                        toolArgs = call.toolArgs,
                        text = body,
                        model = m.model.ifBlank { call.model },
                        // The round's thinking rides on the call row; the result
                        // row has none. Carry it so each tool step keeps its own
                        // "Thought" above the card instead of losing it here.
                        reasoningContent = call.reasoningContent,
                        thinkingMs = call.thinkingMs,
                    )
                } else {
                    out += m
                }
            }
            else -> out += m
        }
    }
    return out
}

/** The call id a stored row's block carries; "" for rows that predate blocks. */
private fun blockCallId(raw: String): String =
    if (raw.isBlank()) "" else runCatching { org.json.JSONObject(raw).optString("id") }.getOrDefault("")
