package com.tulipskun.aixodia.data.remote

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.tulipskun.aixodia.data.model.AiOutput
import com.tulipskun.aixodia.data.model.Block
import com.tulipskun.aixodia.data.model.ToolCall
import com.tulipskun.aixodia.data.model.ToolResultView
import org.json.JSONObject

/**
 * Maps one persisted run event to the frame the chat already speaks.
 *
 * The mapping is pure so it can be tested without OkHttp or Android: the run
 * API stores exactly these kinds and payloads, and a bad mapping would show up
 * as a missing answer rather than a crash.
 */
internal object RunFrames {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val turnAdapter = moshi.adapter(TurnRow::class.java)
    private val blockAdapter = moshi.adapter(Block::class.java)

    fun map(kind: String, data: Any?, session: String): AiOutput? {
        return when (kind) {
            "assistant" -> {
                val text = (data as? JSONObject)?.optString("text").orEmpty()
                if (text.isEmpty()) null
                else AiOutput(sessionId = session, kind = "delta", text = text, agent = "main")
            }
            "reasoning" -> {
                val text = (data as? JSONObject)?.optString("text").orEmpty()
                if (text.isEmpty()) null
                else AiOutput(sessionId = session, kind = "trace", stage = "reasoning", text = text)
            }
            "tool" -> parseTool(data as? JSONObject, session)
            "turn" -> {
                val row = data as? JSONObject ?: return null
                val turn = runCatching { turnAdapter.fromJson(row.toString()) }.getOrNull() ?: return null
                AiOutput(
                    sessionId = session,
                    kind = "message",
                    turns = listOf(turn),
                    turnsSynced = false,
                )
            }
            "final" -> {
                val obj = data as? JSONObject ?: return null
                val turn = obj.optJSONObject("turn") ?: return null
                val row = runCatching { turnAdapter.fromJson(turn.toString()) }.getOrNull() ?: return null
                AiOutput(
                    sessionId = session,
                    kind = "message",
                    text = row.text,
                    seq = row.seq,
                    model = row.model,
                    durationMs = row.durationMs,
                    generationMs = row.generationMs,
                    inputTokens = row.inputTokens,
                    outputTokens = row.outputTokens,
                    cacheRead = row.cacheRead,
                    cacheWrite = row.cacheWrite,
                    reasoningTokens = row.reasoningTokens,
                    inputIncludesCache = row.inputIncludesCache,
                    turns = listOf(row),
                    // The engine's own word on whether its rows have reached the
                    // store. A run event carries only the turn and its usage, so
                    // the seq says the same thing: a turn no store has numbered
                    // is a turn still on its way there, and the phone has to read
                    // it back rather than trust what it was handed.
                    turnsSynced = obj.optBoolean("turns_synced", row.seq > 0L),
                )
            }
            "error" -> {
                val message = (data as? JSONObject)?.optString("message").orEmpty()
                error(message.ifBlank { "the run failed" }, session)
            }
            "status" -> status(data as? JSONObject, session)
            else -> null
        }
    }

    /** True for a status that ends the stream. */
    fun terminal(status: String): Boolean =
        status == "done" || status == "cancelled" || status == "failed" || status == "interrupted"

    fun status(obj: JSONObject?, session: String): AiOutput? {
        val status = obj?.optString("status").orEmpty()
        return when (status) {
            "done" -> AiOutput(sessionId = session, kind = "done", stage = "done", turnsSynced = true)
            "cancelled" -> AiOutput(sessionId = session, kind = "done", stage = "cancelled", turnsSynced = true)
            "failed" -> error(obj?.optString("error").orEmpty().ifBlank { "the run failed" }, session)
            "interrupted" -> error("the engine restarted while this task was running", session)
            else -> null
        }
    }

    fun error(message: String, session: String): AiOutput =
        AiOutput(sessionId = session, kind = "error", text = message, agent = "system")

    private fun parseTool(obj: JSONObject?, session: String): AiOutput? {
        if (obj == null) return null
        val stage = obj.optString("stage")
        if (stage.isBlank()) return null
        if (stage == "compact") {
            val arr = obj.optJSONArray("summary")
            val lines = ArrayList<String>(arr?.length() ?: 0)
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let(lines::add)
                }
            }
            return AiOutput(
                sessionId = session,
                kind = "trace",
                stage = "compact",
                keptTurns = obj.optInt("kept_turns"),
                droppedTurns = obj.optInt("dropped_turns"),
                compactSummary = lines,
            )
        }
        val block = obj.optJSONObject("block")?.let {
            runCatching { blockAdapter.fromJson(it.toString()) }.getOrNull()
        }
        return AiOutput(
            sessionId = session,
            kind = "trace",
            stage = stage,
            text = obj.optString("text"),
            toolCall = if (stage == "tool_call") {
                ToolCall(
                    id = obj.optString("call_id"),
                    name = obj.optString("name"),
                    arguments = obj.optString("arguments"),
                )
            } else null,
            toolResult = if (stage == "tool_result") {
                ToolResultView(
                    id = obj.optString("call_id"),
                    name = obj.optString("name"),
                    text = obj.optString("text"),
                    isError = obj.optBoolean("is_error"),
                )
            } else null,
            toolDurationMs = obj.optLong("duration_ms"),
            inputTokens = obj.optInt("prompt_tokens"),
            outputTokens = obj.optInt("completion_tokens"),
            cacheRead = obj.optInt("cache_read_tokens"),
            cacheWrite = obj.optInt("cache_write_tokens"),
            reasoningTokens = obj.optInt("reasoning_tokens"),
            inputIncludesCache = obj.optBoolean("input_includes_cache"),
            block = block,
        )
    }
}
