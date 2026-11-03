package com.tulipskun.aixodia.ui.display

import com.tulipskun.aixodia.data.model.ChatMessage
import com.tulipskun.aixodia.data.model.normalizedRole

/**
 * One footer per user turn, on its closing main-agent answer. The engine stores
 * narration as assistant rows too, but only the loop's closing row carries the
 * model and aggregate usage/duration. A timestamp or local thinking time alone
 * therefore cannot mark a completed turn.
 */
fun turnFooterIds(messages: List<ChatMessage>): Set<String> = buildSet {
    var lastMain: ChatMessage? = null

    fun finishTurn() {
        lastMain?.takeIf { !it.pending && it.hasTurnSummary }?.let { add(it.id) }
        lastMain = null
    }

    for (message in messages) {
        when {
            message.role == "user" -> finishTurn()
            message.agent.isNotBlank() && message.agent != "main" -> Unit
            normalizedRole(message.role) == "model" -> lastMain = message
            // A following tool means the preceding words were narration, even
            // if an older row happened to carry a model name or token counts.
            message.role.startsWith("tool") -> lastMain = null
        }
    }
    finishTurn()
}

private val ChatMessage.hasTurnSummary: Boolean
    get() = model.isNotBlank() || durationMs > 0L || generationMs > 0L ||
        tokensIn > 0 || tokensOut > 0 || cacheRead > 0 || cacheWrite > 0 || reasoningTokens > 0
