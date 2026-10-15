package com.tulipskun.aixodia.ui.display

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tulipskun.aixodia.R
import com.tulipskun.aixodia.data.model.ChatMessage
import com.tulipskun.aixodia.data.model.isAnswer
import com.tulipskun.aixodia.data.model.totalInputTokens

/** The green a saved token wears, and the amber one that was paid for. */
private val CacheGreen = Color(0xFF3DDC84)
private val OutAmber = Color(0xFFFFB86B)

/**
 * What the session has cost so far, added up from the thread. Counts only: every
 * turn already carries its own footer, where the turn is.
 */
data class SessionTotals(
    /** The whole prompt, cache included — the figure the cache share is a share of. */
    val promptTokens: Int = 0,
    val cacheRead: Int = 0,
    val outTokens: Int = 0,
    /** The turn that finished most recently, and everything before it. */
    val lastTurnMs: Long = 0,
    val totalMs: Long = 0,
) {
    /** The share of the prompt served from cache, in whole percent. */
    val cachePercent: Int
        get() = if (promptTokens <= 0) 0 else (cacheRead * 100 / promptTokens).coerceIn(0, 100)

    /** Nothing has been counted yet, so there is nothing worth a strip. */
    val isEmpty: Boolean get() = promptTokens <= 0 && outTokens <= 0 && totalMs <= 0L
}

/**
 * Adds a thread up. Only the answers carry counts — a question and a tool result
 * have no usage of their own — and a turn that never reported a duration is left
 * out of the times rather than counted as instant.
 */
fun sessionTotals(messages: List<ChatMessage>): SessionTotals {
    var prompt = 0
    var cache = 0
    var out = 0
    var total = 0L
    var last = 0L
    messages.forEach { m ->
        if (!m.isAnswer) return@forEach
        prompt += m.totalInputTokens()
        cache += m.cacheRead
        out += m.tokensOut
        if (m.durationMs > 0L) {
            total += m.durationMs
            last = m.durationMs
        }
    }
    return SessionTotals(prompt, cache, out, last, total)
}

/**
 * The session's totals, as five columns above the composer.
 *
 * A line per figure would cost five lines of the thread's height and would be
 * read one item at a time; five columns cost one line and are read the way a
 * dashboard is — the eye goes straight to the figure that moved. Colour is what
 * tells them apart, and colour is why the labels can stay short enough to fit a
 * fifth of the row: what came in, what the cache saved, what came out, how long
 * the last turn ran, and how long the session has been working.
 *
 * [lastMs] is passed in rather than read off [totals] so a turn in flight can
 * show its own elapsed time before there is a finished turn to count.
 */
@Composable
fun SessionStrip(
    totals: SessionTotals,
    lastMs: Long = totals.lastTurnMs,
    modifier: Modifier = Modifier,
) {
    if (totals.isEmpty) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        // The same hairline the composer card wears: both are cards resting on
        // the page, and the border is what says where the card ends.
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StripColumn(
                icon = Icons.Default.Download,
                labelRes = R.string.stat_in,
                value = countParts(totals.promptTokens),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            StripDivider()
            StripColumn(
                icon = Icons.Default.Bolt,
                labelRes = R.string.stat_cache,
                value = listOf(totals.cachePercent.toString() to "%"),
                tint = CacheGreen,
                modifier = Modifier.weight(1f),
                bar = totals.cachePercent / 100f,
            )
            StripDivider()
            StripColumn(
                icon = Icons.Default.Upload,
                labelRes = R.string.stat_out,
                value = countParts(totals.outTokens),
                tint = OutAmber,
                modifier = Modifier.weight(1f),
            )
            StripDivider()
            StripColumn(
                icon = Icons.Default.Timer,
                labelRes = R.string.stat_last,
                value = formatSpan(lastMs),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            StripDivider()
            StripColumn(
                icon = Icons.Default.Schedule,
                labelRes = R.string.stat_total,
                value = formatSpan(totals.totalMs),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * One column: what it measures, and the figure. The figure sits under the label
 * rather than beside it because it is the one thing on the strip a reader is
 * looking for, and it should not have to be found past an icon.
 */
@Composable
private fun StripColumn(
    icon: ImageVector,
    @StringRes labelRes: Int,
    value: List<Pair<String, String>>,
    tint: Color,
    modifier: Modifier = Modifier,
    /** The filled share of a bar under the figure, when the figure is a share. */
    bar: Float? = null,
) {
    Column(
        modifier.padding(horizontal = 2.dp, vertical = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(11.dp))
            Spacer(Modifier.width(3.dp))
            Text(
                stringResource(labelRes),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 8.sp,
                letterSpacing = 0.7.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(1.dp))
        Text(statText(value), maxLines = 1, softWrap = false)
        if (bar != null) {
            Spacer(Modifier.height(2.dp))
            val track = RoundedCornerShape(1.5.dp)
            Box(
                Modifier
                    .fillMaxWidth(0.72f)
                    .height(2.dp)
                    .clip(track)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                if (bar > 0f) {
                    Box(
                        Modifier
                            .fillMaxWidth(bar.coerceIn(0.02f, 1f))
                            .height(2.dp)
                            .clip(track)
                            .background(tint),
                    )
                }
            }
        }
    }
}

/** The hairline that keeps two figures from reading as one wide number. */
@Composable
private fun StripDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(24.dp)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)),
    )
}

/**
 * A figure with its unit: the number bright and monospaced so the columns do not
 * jump as the counts grow, the unit dim because it is a scale, not a reading.
 */
@Composable
private fun statText(parts: List<Pair<String, String>>): AnnotatedString {
    val bright = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    return buildAnnotatedString {
        parts.forEachIndexed { index, part ->
            if (index > 0) withStyle(SpanStyle(color = dim, fontSize = 8.sp)) { append(" ") }
            withStyle(
                SpanStyle(
                    color = bright,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                ),
            ) { append(part.first) }
            if (part.second.isNotEmpty()) {
                withStyle(SpanStyle(color = dim, fontSize = 8.sp, fontFamily = FontFamily.Monospace)) {
                    append(part.second)
                }
            }
        }
    }
}
