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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
val AnswerBubbleShape = RoundedCornerShape(
    topStart = 18.dp, topEnd = 18.dp, bottomStart = 6.dp, bottomEnd = 18.dp,
)

/** Whole seconds, or one decimal under ten, so a short turn is not "0s". */
fun formatSeconds(millis: Long): String = when {
    millis < 10_000 -> String.format(Locale.US, "%.1fs", millis / 1000.0)
    else -> "${millis / 1000}s"
}

/** Cache usage is a subset of input/output, so it reads as its own clause. */
fun formatCacheText(cacheRead: Int, cacheWrite: Int): String = when {
    cacheRead > 0 && cacheWrite > 0 -> " · cache $cacheRead/$cacheWrite"
    cacheRead > 0 -> " · cache $cacheRead"
    cacheWrite > 0 -> " · cache เขียน $cacheWrite"
    else -> ""
}

fun formatClock(ts: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

/**
 * One message in the thread. User bubbles sit on the right (ChatGPT/Telegram),
 * assistant answers on the left with soft surface, tools as monospace cards.
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
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = if (startsAfterUser) 10.dp else 4.dp, bottom = 4.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (startsAfterUser) {
                // A hairline so an answer does not run into the next question.
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth(if (mine) 0.88f else 0.94f)
                        .padding(bottom = 8.dp),
                )
            }
            if (named) {
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
                text = buildString {
                    if (m.pending) append("กำลังส่ง… · ")
                    append(formatClock(m.createdAt))
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = if (named) 4.dp else 0.dp),
            )
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
        } else {
            Surface(
                color = if (mine) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
                contentColor = if (mine) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                shape = if (mine) UserBubbleShape else AnswerBubbleShape,
                tonalElevation = if (mine) 1.dp else 0.dp,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .widthIn(max = 760.dp)
                    .fillMaxWidth(if (mine) 0.88f else 0.94f)
                    .combinedClickable(onClick = {}, onLongClick = onCopy)
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                MarkdownText(
                    text = body,
                    color = if (mine) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
        if (!mine && !isTool) {
            MessageFooter(m)
        }
    }
}

/**
 * Footer of one answer: model, tokens, duration, rate (AX-095).
 * Blank when the message never carried counts — no zero pretence.
 */
@Composable
fun MessageFooter(m: ChatMessage) {
    val line = buildString {
        if (m.model.isNotBlank()) append(m.model).append(" · ")
        if (m.tokensOut > 0 || m.tokensIn > 0 || m.cacheRead > 0 || m.cacheWrite > 0) {
            append(m.tokensOut).append(" token")
            if (m.tokensIn > 0) append(" (↑").append(m.tokensIn).append(")")
            append(formatCacheText(m.cacheRead, m.cacheWrite))
        }
        if (m.durationMs > 0L) {
            if (isNotEmpty()) append(" · ")
            append(formatSeconds(m.durationMs))
            val rate = if (m.durationMs > 0) m.tokensOut * 1000.0 / m.durationMs else 0.0
            if (m.tokensOut > 0 && rate > 0.0) {
                append(" · ").append(String.format(Locale.US, "%.1f", rate)).append(" tok/s")
            }
        }
    }
    if (line.isBlank()) return
    Text(
        line,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

@Composable
fun AgentBadge(m: ChatMessage) {
    val scheme = MaterialTheme.colorScheme
    val (label, bg, fg) = when {
        m.role == "user" -> Triple("คุณ", scheme.primaryContainer, scheme.onPrimaryContainer)
        m.agent == "main" -> Triple("MAIN", scheme.primaryContainer, scheme.onPrimaryContainer)
        m.agent == "sub" -> Triple("SUB", scheme.tertiaryContainer, scheme.onTertiaryContainer)
        m.agent == "worker" -> Triple("WORKER", scheme.secondaryContainer, scheme.onSecondaryContainer)
        else -> return
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = fg,
        modifier = Modifier
            .padding(end = 8.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
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
    Text(
        text = buildString {
            val route = stats.model.ifBlank { model }
            if (route.isNotBlank()) append(route).append(" · ")
            append(mark).append(tokens).append(" token")
            if (stats.inputTokens > 0) append(" (↑").append(stats.inputTokens).append(")")
            append(formatCacheText(stats.cacheRead, stats.cacheWrite))
            append(" · ").append(elapsed)
            if (rate > 0.0) append(" · ").append(mark).append(String.format(Locale.US, "%.1f", rate)).append(" tok/s")
            if (live && stats.running) append(" · กำลังทำงาน")
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
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
                "sub agent ${agents.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            "${a.label} · ${formatSeconds(a.elapsedMs(nowMs))}",
                            style = MaterialTheme.typography.labelMedium,
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
