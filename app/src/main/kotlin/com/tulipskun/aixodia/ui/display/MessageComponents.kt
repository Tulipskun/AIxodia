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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.tulipskun.aixodia.R
import com.tulipskun.aixodia.data.model.AttachmentRef
import com.tulipskun.aixodia.data.model.ChatMessage
import com.tulipskun.aixodia.data.model.isAnswer
import com.tulipskun.aixodia.data.model.splitAttachmentNote
import com.tulipskun.aixodia.data.model.ToolStep
import com.tulipskun.aixodia.data.model.freshInputTokens
import com.tulipskun.aixodia.data.model.totalInputTokens
import com.tulipskun.aixodia.ui.chat.MarkdownText
import com.tulipskun.aixodia.ui.chat.SubAgentActivity
import com.tulipskun.aixodia.ui.chat.TurnStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The user's own bubble. Evenly rounded, with no tail on the outgoing corner:
 * the bubble is already on the right and already the only blue thing in the
 * thread, so a tail would be a third way of saying the same thing.
 */
val UserBubbleShape = RoundedCornerShape(13.dp)
fun formatClock(ts: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

/**
 * The wall clock a turn wears, on both sides of the conversation.
 *
 * A question wears its clock under the bubble and an answer wears one at the end
 * of its footer, and both are the same reading of the same kind of thing: when
 * this turn landed. They have to be drawn the same way for that to be true. They
 * were not — the question's clock was a step larger and in the body font while
 * the answer's was monospaced, so the two sides looked like two different apps
 * and the question's timestamp read as something other than a time.
 *
 * The figures are monospaced so two clocks stacked in a column line up, and the
 * size is the footer's own, because the footer's line is the tighter of the two
 * and nothing here should make it grow.
 */
@Composable
private fun clockStyle() = MaterialTheme.typography.labelSmall.copy(
    fontSize = 10.sp,
    fontFamily = FontFamily.Monospace,
)

/**
 * The grey a wall clock wears: bright enough to read at a glance, quiet enough
 * to be skipped until it is wanted. The harness draws it at `#7A838F`, which is
 * this tint over the page.
 */
@Composable
private fun clockTint() = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

/**
 * One message in the thread. A user turn is a bubble on the right, an answer is
 * plain text on the background, a tool step is a monospace card.
 */
@Composable
fun MessageBlock(
    m: ChatMessage,
    startsAfterUser: Boolean = false,
    onCopy: () -> Unit = {},
    onRetry: (() -> Unit)? = null,
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
        val clock = if (m.pending) "" else formatClock(m.createdAt)
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
            // The card names the tool and what it was asked to do; the call's own
            // output folds away underneath. A tool block starts collapsed: a long
            // result is the one thing in a thread that is noise until it is asked
            // for, and the header already says which tool ran.
            ToolCallCard(
                name = m.toolName.ifBlank { if (m.role == "tool_result") "result" else "call" },
                args = m.toolArgs,
                body = body,
                durationMs = m.durationMs,
                // A merged step is the result answering the call, so the step is
                // over; a call with no result yet is the one still running.
                done = m.role == "tool_result",
                failed = m.role == "tool_result" && m.text.startsWith("{\"error\""),
                onCopy = onCopy,
                copyLabel = copyLabel,
            )
        } else if (mine) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = UserBubbleShape,
                tonalElevation = 1.dp,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .widthIn(max = 760.dp)
                    // The bubble may only stretch to a maximum of eight-eights
                    // of the row on its own empty broad surface and then it wraps
                    // to the text width; this keeps empty space out of short text.
                    .wrapContentWidth(align = Alignment.End)
                    .clip(UserBubbleShape)
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
                    Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
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
                            fillWidth = false,
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
                }
            }
            if (m.pending) {
                // Still on its way: a spinner and the word, no clock. The
                // clock arrives with the ack, and a time nobody sent yet would
                // read as a measurement rather than as waiting.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp, end = 2.dp),
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(10.dp),
                    )
                    Spacer(Modifier.size(5.dp))
                    Text(
                        stringResource(R.string.sending),
                        maxLines = 1,
                        softWrap = false,
                        style = clockStyle(),
                        color = clockTint(),
                    )
                }
            } else {
                Text(
                    text = clock,
                    maxLines = 1,
                    softWrap = false,
                    style = clockStyle(),
                    color = clockTint(),
                    modifier = Modifier.padding(top = 2.dp, end = 2.dp),
                )
            }
        } else {
            // Every request thought first, so every answer that kept anything
            // of it wears the pill above its words: the phone's own
            // send-to-first-token, and the model's own reasoning where the
            // daemon stored any. Turns from before either existed show nothing
            // rather than a figure that was never measured.
            if (m.thinkingMs > 0L || m.reasoningContent.isNotBlank()) {
                ThoughtPill(m.thinkingMs, m.reasoningContent)
            }
            // An answer is the page, not a bubble: the words sit straight on the
            // background and the turn is read from the gap above it. Only code
            // and quotes earn their own surface, which MarkdownText draws.
            Row(Modifier.fillMaxWidth()) {
                AgentRule(m)
                MarkdownText(
                    text = body,
                    color = MaterialTheme.colorScheme.onSurface,
                    fillWidth = false,
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
            MessageFooter(m, tokenFields, onCopy = onCopy, onRetry = onRetry, copyLabel = copyLabel)
        }
    }
}

/**
 * The footer of one answer: a single line naming the turn — the model, the speed
 * it wrote at, how long the turn took and when it landed.
 *
 * What the line contains, and in what order, lives in [TokenField], not here,
 * because providers differ in what they report and a reader may want a different
 * set. Drawing knows nothing about the format.
 */
@Composable
fun MessageFooter(
    m: ChatMessage,
    fields: List<TokenField> = TokenField.DEFAULT,
    onCopy: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    copyLabel: String = "",
) {
    FooterLine(
        m.toTokenCounts(),
        fields,
        onCopy = onCopy,
        onRetry = onRetry,
        copyLabel = copyLabel,
    )
}

/**
 * One footer line. Each part gets its own mark in front of it so the eye can
 * find the figure it wants without reading the one before it, and the wall clock
 * goes to the far end, where it reads as the end of the turn rather than as
 * another figure in the middle of it.
 *
 * A part is drawn as pieces rather than as one string because the figures are
 * the reading and the words around them are the scale: bright and monospaced for
 * the number, dim for the unit, so a column of footers lines its figures up.
 */
@Composable
fun FooterLine(
    counts: TokenCounts,
    fields: List<TokenField> = TokenField.DEFAULT,
    approximate: Boolean = false,
    modifier: Modifier = Modifier,
    /** Offered after the clock, where the harness puts copy and retry. Null hides. */
    onCopy: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    copyLabel: String = "",
) {
    val entries = counts.entries(fields)
    val clock = counts.clockIfShown(fields)
    if (entries.isEmpty() && clock.isBlank()) return
    Row(
        modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The parts share what the clock leaves, and the clock is measured first
        // because a wall clock that runs into the elapsed time reads as one
        // number — "7.4s20:09" is a time nobody has ever seen. The model name is
        // the only part with no fixed length, so it is the one that gives way.
        Row(
            Modifier.weight(1f).clipToBounds(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            entries.forEachIndexed { index, entry ->
                // The gap is the whole separator now: a dot between two parts
                // read as a third figure in a line that is already dense, and
                // the mark in front of every part is separation enough.
                if (index > 0) Spacer(Modifier.width(10.dp))
                if (entry.field == TokenField.Model) {
                    // The model is named by a dot in the accent rather than by an
                    // icon: it is not a measurement, it is whose turn this was.
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Spacer(Modifier.size(6.dp))
                } else {
                    Icon(footerIcon(entry.field), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.size(4.dp))
                }
                entry.pieces.forEach { piece ->
                    Text(
                        piece.first,
                        style = MaterialTheme.typography.labelSmall,
                        // The harness draws this line at 26px on a 2.8125 px/dp
                        // screen, which is 9.2sp. Ten is what fits the whole
                        // model name on a 360dp thread without reaching for it.
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (piece.second) FontWeight.Normal else FontWeight.SemiBold,
                        color = if (piece.second) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (entry.field == TokenField.Model) {
                            Modifier.weight(1f, fill = false)
                        } else {
                            Modifier
                        },
                    )
                }
            }
        }
        if (clock.isNotBlank()) {
            Spacer(Modifier.width(12.dp))
            Text(
                clock + if (approximate) "\u2248" else "",
                style = clockStyle(),
                color = clockTint(),
                maxLines = 1,
            )
        }
        // What the turn left behind: copy takes its words, retry runs it again.
        // Both sit after the clock because they act on the finished turn, not
        // on any one figure in it, and both stay quiet — the same grey as the
        // clock — until they are wanted.
        if (onCopy != null) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = copyLabel.ifBlank { null },
                tint = clockTint(),
                modifier = Modifier.size(13.dp).combinedClickable(onClick = onCopy),
            )
        }
        if (onRetry != null) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.Refresh,
                contentDescription = stringResource(R.string.retry_turn),
                tint = clockTint(),
                modifier = Modifier.size(14.dp).combinedClickable(onClick = onRetry),
            )
        }
    }
}

/** The mark in front of a footer part: what kind of figure is about to follow. */
private fun footerIcon(field: TokenField): androidx.compose.ui.graphics.vector.ImageVector = when (field) {
    TokenField.Input -> Icons.Default.Download
    TokenField.Output -> Icons.Default.Upload
    TokenField.Rate -> Icons.Default.Bolt
    TokenField.Time -> Icons.Default.Schedule
    TokenField.Model -> Icons.Default.Schedule
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
    // The rate is measured over the time the model spent writing and the
    // elapsed figure over the whole turn, because they answer different
    // questions: how fast does it type, and how long did I wait. Dividing by
    // the turn would fold the queue and every tool into "writing", which made a
    // fast answer read as 1t/s. Rows stored before the split carry no
    // generation time, so they fall back to the turn duration.
    ratePerSecond = if ((generationMs > 0 || durationMs > 0) && tokensOut > 0) {
        tokensOut * 1000.0 / (if (generationMs > 0) generationMs else durationMs).toDouble()
    } else 0.0,
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
 * The answer while it is still streaming: the words so far and the block
 * cursor where the next one lands. No header — the thinking card above already
 * said whose turn this is — and no footer here either: the one live turn-meta
 * line under the whole live turn covers thinking, tools and words alike.
 */
@Composable
fun LiveAnswer(text: String) {
    val pulse by rememberInfiniteTransition(label = "live-cursor").animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "live-cursor-alpha",
    )
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            MarkdownText(
                text = text,
                modifier = Modifier.padding(top = 2.dp).weight(1f, fill = false),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
            )
            // The block the harness draws at the insertion point. It rides at
            // the row's end rather than mid-line: close enough to read as
            // "still writing" without re-laying every word around it.
            Box(
                Modifier
                    .padding(top = 6.dp, start = 4.dp)
                    .size(width = 7.dp, height = 15.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = pulse)),
            )
        }
    }
}

/**
 * What a finished turn kept of its thinking: send to first word, measured on
 * the phone, and what the model actually thought, as the daemon stored it.
 *
 * The pill opens when there is something behind it — the daemon's own
 * reasoning text — and stays closed otherwise: a chevron that opens onto
 * nothing would be the same lie the live pill refused.
 */
@Composable
private fun ThoughtPill(thinkingMs: Long, reasoning: String) {
    var open by remember { mutableStateOf(false) }
    val canOpen = reasoning.isNotBlank()
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = CircleShape,
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Column {
            Row(
                Modifier
                    .padding(start = 12.dp, end = 14.dp, top = 6.dp, bottom = 6.dp)
                    .then(
                        if (canOpen) {
                            Modifier.combinedClickable(
                                onClick = { open = !open },
                                onLongClick = null,
                                onLongClickLabel = null,
                            )
                        } else {
                            Modifier
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    if (thinkingMs > 0L) {
                        stringResource(R.string.thought_for, formatSeconds(thinkingMs))
                    } else {
                        stringResource(R.string.thought)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (canOpen) {
                    Spacer(Modifier.size(4.dp))
                    Icon(
                        if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = stringResource(
                            if (open) R.string.collapse else R.string.expand,
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            AnimatedVisibility(
                visible = open && canOpen,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                // The model's own words, in the same recess a tool's output
                // sits in: a different kind of text from the thread around it.
                // Capped in height with its own scroll, so a long chain of
                // thought does not push the answer off the screen.
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
                ) {
                    Text(
                        reasoning,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

/**
 * The thinking of the turn in flight, as the harness draws it: a full-width
 * card with elapsed time and the reasoning text upstream actually exposed.
 * Every request shows the header; absent reasoning leaves the body empty.
 */
@Composable
fun ThinkingLive(
    elapsedMs: Long,
    reasoning: String,
    modifier: Modifier = Modifier,
) {
    val pulse by rememberInfiniteTransition(label = "thinking-live").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "thinking-live-alpha",
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = pulse),
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(R.string.thinking_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    formatSeconds(elapsedMs),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            val lines = reasoning.lines().filter { it.isNotBlank() }.takeLast(4)
            if (lines.isNotEmpty()) {
                Spacer(Modifier.size(8.dp))
                // The row is only as tall as its lines, and the accent fills
                // that height: without the intrinsic the bar has nothing to
                // fill against and collapses to nothing.
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(
                        Modifier
                            .width(1.5.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(1.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                    )
                    Spacer(Modifier.size(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        lines.forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
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
        steps.forEach { ToolStepCard(it) }
    }
}

/** One tool step of the live turn, on the same card a stored step gets. */
@Composable
fun ToolStepCard(step: ToolStep, nowMs: Long = 0L) {
    val running = !step.done
    ToolCallCard(
        name = step.name,
        args = step.args,
        body = step.result,
        durationMs = step.durationMs,
        done = step.done,
        failed = step.isError,
        running = running,
        runningMs = if (running && step.startedAtMs > 0L && nowMs > 0L) {
            (nowMs - step.startedAtMs).coerceAtLeast(0L)
        } else {
            0L
        },
    )
}

/**
 * The command a running step was given, with the block cursor where its output
 * will land. The `$` says whose line this is, the way a terminal does.
 */
@Composable
private fun CommandLine(command: String) {
    val pulse by rememberInfiniteTransition(label = "tool-cursor").animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "tool-cursor-alpha",
    )
    Row(
        Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(6.dp))
        Text(
            command,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.size(4.dp))
        Box(
            Modifier
                .size(width = 7.dp, height = 14.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = pulse)),
        )
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
    wallClockMs: Long = System.currentTimeMillis(),
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
        // A turn in flight has no landing time yet, so the live line ends at
        // its elapsed time. The settled footer gains the wall clock instead.
        clock = if (live) "" else formatClock(wallClockMs),
    )
    if (counts.visible(fields).isEmpty()) return
    // The same line, marked with the ≈ that says the counts are estimates until
    // the provider reports the real ones.
    FooterLine(counts = counts, fields = fields, approximate = !stats.exact)
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
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
                FilledTonalButton(onClick = onRetry) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("  " + stringResource(R.string.retry))
                }
            }
        }
    }
}


/**
 * One tool step of a turn, as a card: a badge, the tool's name, the one thing it
 * was asked to do, and how it ended.
 *
 * The header is not a sentence. The name is what a reader scans a thread for,
 * the arguments say which call this was — the same tool runs several times in a
 * turn — and the outcome is a single mark at the end of the row rather than a
 * word per step, because a word per step is a word to read on every step. The
 * output itself folds away underneath, where a long result is quiet until it is
 * asked for.
 */
@Composable
fun ToolCallCard(
    name: String,
    args: String,
    body: String,
    durationMs: Long,
    done: Boolean,
    failed: Boolean,
    /** Left null where there is nothing to copy yet, e.g. a step in flight. */
    onCopy: (() -> Unit)? = null,
    copyLabel: String = "",
    /**
     * A step still running draws itself open: the command it runs, a spinner
     * with its own elapsed time, and the block cursor where output lands. A
     * finished step keeps whatever the reader left it — open or folded.
     */
    running: Boolean = false,
    runningMs: Long = 0L,
) {
    var expanded by remember { mutableStateOf(false) }
    val open = expanded || running
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    // A step in flight is watched, not worked: tapping it folds nothing.
    val clickable = Modifier.then(
        if (running) Modifier
        else Modifier.combinedClickable(
            onClick = { expanded = !expanded },
            onLongClick = onCopy,
            // No long press, no label for one: a press that does nothing
            // announces itself as a button and then does not behave like one.
            onLongClickLabel = copyLabel.ifBlank { null },
        ),
    )
    Surface(
        color = scheme.surfaceContainerHigh,
        shape = shape,
        modifier = Modifier
            .padding(top = 4.dp)
            .fillMaxWidth()
            .clip(shape)
            .then(clickable),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // The badge is what makes a tool step recognisable at arm's
                // length without reading it: the same small dark square on
                // every one of them.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(scheme.surfaceContainerHighest),
                ) {
                    Text(
                        ">_",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = scheme.primary,
                    )
                }
                Spacer(Modifier.size(8.dp))
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (failed) scheme.error else scheme.onSurface,
                        maxLines = 1,
                    )
                    if (args.isNotBlank()) {
                        Spacer(Modifier.size(6.dp))
                        // The arguments are cut rather than wrapped: the header
                        // is a label, and all of it is in the body underneath.
                        Text(
                            args,
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (done) {
                    Spacer(Modifier.size(6.dp))
                    Icon(
                        if (failed) Icons.Default.Close else Icons.Default.Check,
                        contentDescription = stringResource(if (failed) R.string.tool_failed else R.string.tool_done),
                        tint = if (failed) scheme.error else scheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                }
                if (running) {
                    Spacer(Modifier.size(6.dp))
                    CircularProgressIndicator(
                        strokeWidth = 1.5.dp,
                        color = scheme.primary,
                        modifier = Modifier.size(12.dp),
                    )
                }
                if (durationMs > 0L || running) {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        formatSeconds(if (running) runningMs else durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                // A step in flight is already open, so it wears no chevron: the
                // mark would promise a fold that is not offered while it runs.
                if (!running) {
                    Spacer(Modifier.size(6.dp))
                    Icon(
                        if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = stringResource(if (open) R.string.collapse else R.string.expand),
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            AnimatedVisibility(
                visible = open,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                // The output sits in its own recess, the way a code block does:
                // it is a different kind of text from the thread around it.
                Surface(
                    color = scheme.surfaceContainerLowest,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    if (running && body.isBlank()) {
                        // Nothing has come back yet: the command with the block
                        // cursor where its output will land.
                        CommandLine(args.ifBlank { name })
                    } else {
                        Text(
                            body.ifBlank { args },
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
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
