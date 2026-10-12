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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.tulipskun.aixodia.R
import com.tulipskun.aixodia.data.model.AttachmentRef
import com.tulipskun.aixodia.data.model.ChatMessage
import com.tulipskun.aixodia.data.model.splitAttachmentNote
import com.tulipskun.aixodia.data.model.ToolStep
import com.tulipskun.aixodia.data.model.freshInputTokens
import com.tulipskun.aixodia.data.model.totalInputTokens
import com.tulipskun.aixodia.ui.LocalUiSpec
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
    tokenFields: List<TokenField> = TokenField.DEFAULT,
    copyLabel: String = "",
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
        // a chat app does. An answer carries it in its own footer line instead, so
        // the strip above is left for the badge and the tool name only.
        // The strip above an answer exists only to name who spoke and which tool
        // ran. The clock is in the footer next to the time used, so a main agent
        // answer draws no strip at all rather than an empty one.
        // A tool block names its own tool in its header, so repeating it on a
        // strip above the block printed the same word twice in a row.
        if (!mine && showBadge && !isTool) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AgentBadge(m)
            }
        }
        if (isTool) {
            // The header line names the tool and its arguments, the body expands
            // on tap. Whether it starts open, how the header is worded and how
            // much of the arguments it shows all come from the daemon's UI spec.
            val ui = LocalUiSpec.current
            val tools = ui.toolBlock
            var expanded by remember { mutableStateOf(!tools.collapsedByDefault) }
            val tint = when {
                m.role == "tool_result" && m.text.startsWith("{\"error\"") -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .widthIn(max = 760.dp)
                    .fillMaxWidth(if (mine) 1f else 0.94f)
                    .combinedClickable(
                        onClick = { expanded = !expanded },
                        onLongClick = onCopy,
                        onLongClickLabel = copyLabel,
                    ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            toolHeader(
                                template = tools.headerTemplate,
                                name = m.toolName.ifBlank { if (m.role == "tool_result") "result" else "call" },
                                args = m.toolArgs,
                                expanded = expanded,
                                showIcon = tools.showIcon,
                                maxArgs = tools.maxArgsChars,
                                duration = if (tools.showDuration) m.durationMs else 0L,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            color = tint,
                            modifier = Modifier.weight(1f).padding(end = 10.dp),
                        )
                        Icon(
                            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) ui.collapse else ui.expand,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AnimatedVisibility(
                        visible = expanded,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        Column {
                            if (m.toolArgs.isNotBlank() && m.text.isNotBlank()) {
                                Text(
                                    m.toolArgs,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            }
                            Text(
                                body,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
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
                    .combinedClickable(
                        // A tap that does nothing is worse than no tap: it
                        // announces itself as a button and then does not
                        // behave like one. Only the long press that copies is
                        // offered, and it says so.
                        onClick = {},
                        onLongClick = onCopy,
                        onLongClickLabel = copyLabel,
                    ),
            ) {
                val parts = splitAttachmentNote(body)
                Column(
                    Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 6.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    // Padding goes on the content, not on the Surface's own
                    // modifier chain: a Surface clips what it lays out to its
                    // shape, so padding the surface itself shaved the first
                    // character off the left of every line of every message.
                    if (parts.text.isNotBlank()) {
                        MarkdownText(
                            text = parts.text,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    // The file names ride along as chips; the server path and
                    // the note aimed at the agent stay out of the bubble.
                    if (parts.attachments.isNotEmpty()) {
                        Row(
                            Modifier
                                .padding(top = 4.dp)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            parts.attachments.forEach { AttachmentChip(it) }
                        }
                    }
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
                        .combinedClickable(
                        // A tap that does nothing is worse than no tap: it
                        // announces itself as a button and then does not
                        // behave like one. Only the long press that copies is
                        // offered, and it says so.
                        onClick = {},
                        onLongClick = onCopy,
                        onLongClickLabel = copyLabel,
                    ),
                )
            }
        }
        if (!mine && !isTool) {
            MessageFooter(m, tokenFields)
        }
    }
}

/**
 * The footer of one answer: the two token splits, the rate, the model and how
 * long it took, one item per line.
 *
 * The wording, the order and which lines appear live in [TokenField], not here,
 * because providers differ in what they report and a reader may want a
 * different set. Drawing knows nothing about the format.
 */
@Composable
fun MessageFooter(m: ChatMessage, fields: List<TokenField> = TokenField.DEFAULT) {
    val counts = m.toTokenCounts()
    val shown = counts.visible(fields)
    if (shown.isEmpty()) return
    Text(
        counts.render(fields),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
    )
}

/**
 * This message's counts, already split by the convention its provider used.
 * The cache numbers are the input side and the reasoning count is taken off the
 * answer, because those are the tokens that produced no words anyone read.
 */
fun ChatMessage.toTokenCounts(): TokenCounts = TokenCounts(
    cacheRead = cacheRead,
    // The second half of in: is the part of the prompt the cache could not help
    // with — total input minus what the cache served. Derived by subtraction from
    // the two numbers the provider gave, so it does not depend on a convention
    // flag: every row on the device, including those recorded before that flag
    // existed, adds up to the prompt. When the cache figure exceeds the prompt
    // figure the provider counted them separately, and then all of the prompt
    // is fresh.
    cacheWrite = if (cacheRead <= tokensIn) tokensIn - cacheRead else tokensIn,
    // Anthropic has no thinking count in its usage; OpenAI and Gemini report a
    // real one, zero on a model that does not think.
    reportsReasoning = inputIncludesCache,
    reasoning = reasoningTokens,
    output = (tokensOut - reasoningTokens).coerceAtLeast(0),
    ratePerSecond = if (durationMs > 0 && tokensOut > 0) tokensOut * 1000.0 / durationMs else 0.0,
    millis = durationMs,
    model = model,
    clock = if (createdAt > 0L) formatClock(createdAt) else "",
)

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
    val (labelRes, color) = when {
        m.role == "user" -> Pair(R.string.agent_user, scheme.primary)
        m.agent == "sub" -> Pair(R.string.agent_sub, scheme.tertiary)
        m.agent == "worker" -> Pair(R.string.agent_worker, scheme.secondary)
        else -> return
    }
    val label = stringResource(labelRes)
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
fun LiveAnswer(
    text: String,
    stats: TurnStats,
    routeLabel: String,
    nowMs: Long,
    tokenFields: List<TokenField> = TokenField.DEFAULT,
) {
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
            Text(
                stringResource(R.string.live_answering),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = pulse),
            )
        }
        MarkdownText(
            text = text,
            modifier = Modifier.padding(top = 2.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
        )
        TurnStatsLine(stats = stats, model = routeLabel, nowMs = nowMs, live = true, fields = tokenFields)
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
            LocalUiSpec.current.thinking + " · " + formatSeconds(reasoningMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The tool calls of the turn in flight, as one compact row each. A long
 * sentence per step ("— เสร็จแล้ว", "เครื่องมือ 3") is noise: the colour says
 * whether it is running, done or failed, and the name plus its arguments say
 * what it is doing.
 */
@Composable
fun ToolSteps(steps: List<ToolStep>) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        steps.forEach { step ->
            val tint = when {
                !step.done -> MaterialTheme.colorScheme.tertiary
                step.isError -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                text = buildString {
                    append(if (step.done) "▸ " else "▾ ")
                    append(step.name)
                    if (step.args.isNotBlank()) append(" ").append(step.args.take(60))
                    if (step.durationMs > 0L) append(" · ").append(formatSeconds(step.durationMs))
                },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = tint,
                maxLines = 2,
            )
        }
    }
}

/**
 * The same footer, while the answer is still streaming. It reuses the format
 * rather than repeating it, so a reader who learned the settled line has not
 * learned a second language when the turn is still running. The ≈ marks the
 * counts that are estimates until the provider reports the real ones.
 */
@Composable
fun TurnStatsLine(
    stats: TurnStats,
    model: String,
    nowMs: Long,
    live: Boolean,
    fields: List<TokenField> = TokenField.DEFAULT,
) {
    if (stats.startedAtMs == 0L && model.isBlank()) return
    val tokens = stats.outputTokensNow()
    val counts = TokenCounts(
        cacheRead = stats.cacheRead,
        cacheWrite = stats.cacheWrite,
        reasoning = stats.reasoningTokens,
        output = (tokens - stats.reasoningTokens).coerceAtLeast(0),
        ratePerSecond = stats.tokensPerSecond(nowMs),
        millis = stats.elapsedMs(nowMs),
        model = stats.model.ifBlank { model },
    )
    if (counts.visible(fields).isEmpty()) return
    Text(
        text = counts.render(fields) + if (stats.exact) "" else "\u2248",
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
    // The "running" line was a fourth line under a turn that already says it is
    // streaming; the rate and the clock are what a reader actually wants.
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
    tokenFields: List<TokenField> = TokenField.DEFAULT,
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
                stringResource(R.string.sub_agent_title, agents.size),
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
                            Text("  " + stringResource(R.string.sub_agent_stop))
                        }
                    }
                }
            }
        }
    }
}


/**
 * A failed turn as a card with a retry button — the error is actionable, not
 * just a line of red text. Shown in the thread where the failure happened.
 */
@Composable
fun ErrorCard(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ui = LocalUiSpec.current
    val card = ui.errorCard
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = Modifier
            .padding(top = 4.dp)
            .widthIn(max = 760.dp)
            .fillMaxWidth(0.94f),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                if (card.showDismiss) TextButton(onClick = onDismiss) { Text(ui.dismiss) }
                if (card.showRetry) {
                    FilledTonalButton(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text("  " + ui.retry)
                    }
                }
            }
        }
    }
}


/**
 * The one-line header of a tool block, built from the daemon's template. The
 * placeholders are {icon}, {name}, {args} and {duration}; any of them missing
 * from the template simply does not appear.
 */
fun toolHeader(
    template: String,
    name: String,
    args: String,
    expanded: Boolean,
    showIcon: Boolean,
    maxArgs: Int,
    duration: Long,
): String {
    val icon = if (!showIcon) "" else if (expanded) "▾" else "▸"
    val shortArgs = if (maxArgs > 0) args.take(maxArgs) else args
    val durationText = if (duration > 0L) formatSeconds(duration) else ""
    return template
        .replace("{icon}", icon)
        .replace("{name}", name)
        .replace("{args}", shortArgs)
        .replace("{duration}", durationText)
        .trim()
        .replace(Regex("\\s+"), " ")
}


/** One attached file inside a user bubble: the name and its kind, nothing else. */
@Composable
private fun AttachmentChip(file: AttachmentRef) {
    val kind = when {
        file.mime.startsWith("image/") -> "รูปภาพ"
        file.mime.startsWith("video/") -> "วิดีโอ"
        file.mime.startsWith("audio/") -> "เสียง"
        file.mime.startsWith("text/") -> "ข้อความ"
        file.mime.isBlank() -> ""
        else -> file.mime.substringBefore('/')
    }
    Surface(
        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.16f),
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            Modifier.padding(start = 8.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                if (kind.isBlank()) file.name else "${file.name} · $kind",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
            )
        }
    }
}
