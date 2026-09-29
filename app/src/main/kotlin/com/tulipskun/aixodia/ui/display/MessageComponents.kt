@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.tulipskun.aixodia.ui.display

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tulipskun.aixodia.data.model.ChatMessage
import com.tulipskun.aixodia.data.model.ToolStep
import com.tulipskun.aixodia.ui.chat.MarkdownText
import com.tulipskun.aixodia.ui.chat.SubAgentActivity
import com.tulipskun.aixodia.ui.chat.TurnStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Telegram/ChatGPT-style bubble corners: tail on the outgoing side. */
val UserBubbleShape = RoundedCornerShape(
    topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 6.dp,
)
/** Whole seconds, or one decimal under ten, so a short turn is not "0s". */
fun formatSeconds(millis: Long): String = when {
    millis < 10_000 -> String.format(Locale.US, "%.1fs", millis / 1000.0)
    else -> "${millis / 1000}s"
}

/** Cache written rather than read, which is the rarer and more interesting half. */
fun formatCacheWriteText(cacheWrite: Int): String =
    if (cacheWrite > 0) "เขียน $cacheWrite" else ""

fun formatClock(ts: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

/**
 * One message in the thread. A user turn is a bubble on the right, an answer is
 * plain text on the background, a tool step is a monospace card.
 */
@Composable
fun MessageBlock(
    m: ChatMessage,
    startsAfterUser: Boolean = false,
    onCopy: () -> Unit = {},
) {
    val mine = m.role == "user"
    val isTool = m.role == "tool_call" || m.role == "tool_result"
    val body = m.text.ifBlank { m.toolArgs }
    // The main agent is the conversation itself, so it carries no name; a sub
    // agent or a tool step keeps its badge because it is a separate voice.
    val named = (m.agent.isNotBlank() && m.agent != "main") || isTool
    // No "คุณ" badge: the bubble on the right already says whose turn it is,
    // and a label on every question is noise. The clock is enough.
    val showBadge = named && m.role != "user"
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = if (startsAfterUser) 10.dp else 4.dp, bottom = 4.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        val clock = buildString {
            if (m.pending) append("กำลังส่ง… · ")
            append(formatClock(m.createdAt))
        }
        // A question wears the clock inside its own bubble, bottom right, the way
        // a chat app does. An answer and a tool card keep it above, in the
        // quiet strip that belongs to them.
        val clockInBubble = mine
        if (!clockInBubble) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showBadge) {
                    AgentBadge(m)
                    if (m.toolName.isNotBlank()) {
                        Text(
                            m.toolName,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                Text(
                    text = clock,
                    // The clock is five characters; it has to stay one line.
                    maxLines = 1,
                    softWrap = false,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = if (showBadge) 4.dp else 0.dp),
                )
            }
        }
        if (isTool) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .widthIn(max = 760.dp)
                    .fillMaxWidth(if (mine) 1f else 0.94f)
                    .combinedClickable(onClick = {}, onLongClick = onCopy),
            ) {
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        } else if (mine) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = UserBubbleShape,
                tonalElevation = 1.dp,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .widthIn(max = 760.dp)
                    // The bubble takes the width of the text and stops there. A
                    // fixed 88% meant "ok" and a three-line question drew exactly
                    // the same slab, which is not how a bubble reads.
                    .fillMaxWidth(0.88f)
                    .wrapContentWidth(align = Alignment.End)
                    .combinedClickable(onClick = {}, onLongClick = onCopy),
            ) {
                Column(
                    Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 6.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    // Padding goes on the content, not on the Surface's own
                    // modifier chain: a Surface clips what it lays out to its
                    // shape, so padding the surface itself shaved the first
                    // character off the left of every line of every message.
                    MarkdownText(
                        text = body,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = clock,
                        maxLines = 1,
                        softWrap = false,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        } else {
            // An answer is the page, not a bubble: the words sit straight on the
            // background and the turn is read from the gap above it. Only code
            // and quotes earn their own surface, which MarkdownText draws.
            Row(Modifier.fillMaxWidth()) {
                AgentRule(m)
                MarkdownText(
                    text = body,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(top = if (startsAfterUser) 14.dp else 4.dp)
                        .padding(start = if (m.agent != "main" && m.agent.isNotBlank()) 10.dp else 0.dp)
                        .widthIn(max = 760.dp)
                        .weight(1f, fill = false)
                        .combinedClickable(onClick = {}, onLongClick = onCopy),
                )
            }
        }
        if (!mine && !isTool) {
            MessageFooter(m)
        }
    }
}

/**
 * Footer of one answer: the model, then what it cost, then how long it took.
 *
 * Every count is named. The earlier line was `238 tokens (↑63701) · cache
 * 63424`, which asked the reader to know that the two providers count the
 * prompt differently — and whichever convention was in force, a cache number
 * sitting next to a smaller input number looked like an error. Now the input is
 * split the way the provider split it: `อ่านใหม่` is the part that was not
 * cached, `จาก cache` is the part that was, and they add up to the prompt.
 */
@Composable
fun MessageFooter(m: ChatMessage) {
    Row(
        Modifier.fillMaxWidth().padding(top = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (m.model.isNotBlank()) {
            Text(
                m.model,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        val fresh = m.freshInputTokens()
        val cache = m.cacheRead
        val reasoning = m.reasoningTokens
        val answer = m.tokensOut - reasoning
        // Only the parts that are actually there, so an answer that reported
        // nothing never shows a row of zeroes pretending to be a measurement.
        if (fresh > 0) Count(label = "อ่านใหม่", value = fresh)
        if (cache > 0) Count(label = "จาก cache", value = cache)
        val write = formatCacheWriteText(m.cacheWrite)
        if (write.isNotBlank()) Count(label = "cache", value = write)
        if (reasoning > 0) Count(label = "คิด", value = "$reasoning")
        if (m.tokensOut > 0) Count(label = "ตอบ", value = answer.coerceAtLeast(0).toString())
        if (m.durationMs > 0L) {
            Text(
                formatSeconds(m.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * The margin rule that says "this answer came from someone else". A two-pixel
 * line in the agent's own colour is enough to separate the voice from the main
 * thread, and it costs no reading at all.
 */
@Composable
fun AgentRule(m: ChatMessage) {
    if (m.role == "user" || m.agent == "main" || m.agent.isBlank()) return
    val color = when (m.agent) {
        "sub" -> MaterialTheme.colorScheme.tertiary
        "worker" -> MaterialTheme.colorScheme.secondary
        else -> return
    }
    Spacer(
        Modifier
            .width(2.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(1.dp))
            .background(color),
    )
}

/** One labelled count. The label is dim, the number is not, so a row scans. */
@Composable
private fun Count(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 1,
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.padding(start = 3.dp),
        )
    }
}

/**
 * Who is speaking, as a colour rather than a word.
 *
 * Three labels per turn — MAIN, SUB, WORKER — is three things to read before
 * the answer, and MAIN is the one that needs no label at all because it is the
 * conversation. A sub answer is now a thin coloured rule in the margin with the
 * agent's name in that same colour above it: a reader knows it is a separate
 * voice from the shape, not from decoding a badge.
 */
@Composable
fun AgentBadge(m: ChatMessage) {
    val scheme = MaterialTheme.colorScheme
    val (label, color) = when {
        m.role == "user" -> Pair("คุณ", scheme.primary)
        m.agent == "sub" -> Pair("SUB", scheme.tertiary)
        m.agent == "worker" -> Pair("WORKER", scheme.secondary)
        else -> return
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier.padding(end = 8.dp),
    )
}

/**
 * Live streaming answer with a soft pulse on the status line — typical chatbot
 * "typing" affordance without inventing content.
 */
@Composable
fun LiveAnswer(text: String, stats: TurnStats, routeLabel: String, nowMs: Long) {
    val pulse by rememberInfiniteTransition(label = "live-answer").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "live-answer-alpha",
    )
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AgentBadge(ChatMessage(id = "live", sessionId = "", seq = 0, role = "model", text = "", createdAt = 0, agent = "main"))
            Text(
                "กำลังตอบ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = pulse),
            )
        }
        MarkdownText(
            text = text,
            modifier = Modifier.padding(top = 2.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
        )
        TurnStatsLine(stats = stats, model = routeLabel, nowMs = nowMs, live = true)
    }
}

@Composable
fun ThinkingLine(reasoningMs: Long, agent: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentBadge(ChatMessage(id = "thinking", sessionId = "", seq = 0, role = "model", text = "", createdAt = 0, agent = agent))
        Text(
            "กำลังคิด · " + formatSeconds(reasoningMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun ToolSteps(steps: List<ToolStep>) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "เครื่องมือ ${steps.size}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        steps.forEach { step ->
            val tint = if (!step.done) {
                MaterialTheme.colorScheme.tertiary
            } else if (step.isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                text = buildString {
                    append("● ")
                    append(step.name)
                    if (step.args.isNotBlank()) append(" ").append(step.args.take(40))
                    if (step.durationMs > 0L) append(" · ").append(formatSeconds(step.durationMs))
                    append(
                        when {
                            !step.done -> "…"
                            step.isError -> " — ล้มเหลว"
                            else -> " — เสร็จแล้ว"
                        },
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = tint,
            )
        }
    }
}

/**
 * Footer numbers while streaming (≈) or after the provider reports exact usage.
 */
@Composable
fun TurnStatsLine(stats: TurnStats, model: String, nowMs: Long, live: Boolean) {
    if (stats.startedAtMs == 0L && model.isBlank()) return
    val elapsed = formatSeconds(stats.elapsedMs(nowMs))
    val tokens = stats.outputTokensNow()
    val rate = stats.tokensPerSecond(nowMs)
    val mark = if (stats.exact) "" else "≈"
    // The live line labels the same counts as the settled footer, so a reader
    // who learned one does not have to learn the other. Input is still an
    // estimate while the answer streams, which the ≈ marks.
    val line = buildString {
        val route = stats.model.ifBlank { model }
        if (route.isNotBlank()) append(route).append(" · ")
        val fresh = stats.freshInputTokens()
        if (fresh > 0) append(mark).append("อ่านใหม่ ").append(fresh)
        if (stats.cacheRead > 0) {
            if (isNotEmpty()) append(" · ")
            append("จาก cache ").append(stats.cacheRead)
        }
        val write = formatCacheWriteText(stats.cacheWrite)
        if (write.isNotBlank()) {
            if (isNotEmpty()) append(" · ")
            append("cache ").append(write)
        }
        if (stats.reasoningTokens > 0) {
            if (isNotEmpty()) append(" · ")
            append("คิด ").append(stats.reasoningTokens)
        }
        if (isNotEmpty()) append(" · ")
        append(mark).append("ตอบ ").append((tokens - stats.reasoningTokens).coerceAtLeast(0))
        append(" · ").append(elapsed)
        if (rate > 0.0) append(" · ").append(mark).append(String.format(Locale.US, "%.1f", rate)).append(" tok/s")
        if (live && stats.running) append(" · กำลังทำงาน")
    }
    Text(
        text = line,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

/**
 * Sub-agent activity rows with per-job stop — stays in the display module so
 * the chat thread only composes it.
 */
@Composable
fun SubAgentPanel(
    agents: List<SubAgentActivity>,
    nowMs: Long,
    onStop: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "SUB AGENT ${agents.size}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary,
            )
            agents.forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (dot, tint) = when {
                        a.stopping -> "◐" to MaterialTheme.colorScheme.tertiary
                        a.running -> "●" to MaterialTheme.colorScheme.primary
                        else -> "○" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(dot, color = tint, style = MaterialTheme.typography.labelMedium)
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(
                            a.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        Text(
                            formatSeconds(a.elapsedMs(nowMs)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (a.detail.isNotBlank()) {
                            Text(
                                a.detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                    if (a.running && !a.stopping) {
                        FilledTonalButton(
                            onClick = { onStop(a.jobId) },
                            modifier = Modifier.padding(start = 4.dp),
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("  หยุด")
                        }
                    }
                }
            }
        }
    }
}
