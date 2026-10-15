package com.tulipskun.aixodia.ui.display

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.tulipskun.aixodia.data.model.freshInputTokens
import com.tulipskun.aixodia.data.model.isAnswer
import com.tulipskun.aixodia.data.model.totalInputTokens

/**
 * How loud each part of the bar is. The prompt is split by where the tokens came
 * from and the three parts are told apart by weight rather than by hue: what the
 * cache served is the palest, what had to be paid for is brighter, and what the
 * model wrote is the brightest thing on the strip. Hue would fight the accent,
 * and the accent is reserved for what is live.
 */
private const val CachedAlpha = 0.30f
private const val NewAlpha = 0.65f

/** The width the bar's three parts keep apart, and the height they share. */
private val BarGap = 1.5.dp
private val BarHeight = 3.dp

/** How far apart two legend entries stand, now that no dot separates them. */
private val LegendGap = 12.dp

/**
 * What the session has cost so far, added up from the thread. Counts only: every
 * turn already carries its own footer, where the turn is.
 */
data class SessionTotals(
    /** The whole prompt, cache included — the figure the cache share is a share of. */
    val promptTokens: Int = 0,
    val cacheRead: Int = 0,
    /** The prompt minus what the cache served: the input that had to be paid for. */
    val freshInput: Int = 0,
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
    var fresh = 0
    var out = 0
    var total = 0L
    var last = 0L
    messages.forEach { m ->
        if (!m.isAnswer) return@forEach
        prompt += m.totalInputTokens()
        cache += m.cacheRead
        fresh += m.freshInputTokens()
        out += m.tokensOut
        if (m.durationMs > 0L) {
            total += m.durationMs
            last = m.durationMs
        }
    }
    return SessionTotals(prompt, cache, fresh, out, last, total)
}

/**
 * The session's totals, as one thin bar above the composer.
 *
 * The figures are the same four the thread has been carrying, but the prompt is
 * no longer one number with a cache percentage beside it: the bar splits the
 * prompt into what the cache served and what had to be paid for, and puts that
 * split next to what came out, so the width of each part *is* the reading. The
 * legend under it repeats the three figures in words, because a bar without its
 * numbers only says which part is bigger, not by how much, and the whole of the
 * session's time sits at the far end, away from the counts it is not one of.
 *
 * [liveMs] is a turn still in flight. It is added to the total rather than shown
 * beside it, so the session's clock keeps moving after the first answer lands
 * but the strip never grows a fifth figure.
 */
@Composable
fun SessionStrip(
    totals: SessionTotals,
    liveMs: Long = 0L,
    modifier: Modifier = Modifier,
) {
    if (totals.isEmpty) return
    val elapsed = totals.totalMs + liveMs.coerceAtLeast(0L)
    val cachedTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = CachedAlpha)
    val newTint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = NewAlpha)
    val outTint = MaterialTheme.colorScheme.onSurface

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        // Height is the only thing that decides this shape: the pill's radius is
        // half its own height, which is what keeps it a capsule at any density.
        shape = RoundedCornerShape(percent = 50),
        // The same hairline the composer card wears: both are cards resting on
        // the page, and the border is what says where the card ends.
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 13.dp, vertical = 3.dp),
        ) {
            // The three parts are always drawn: a count that is zero is a
            // reading too — it is how a reader learns the cache is not being
            // used — so the bar and its legend keep their shape.
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(BarHeight),
                horizontalArrangement = Arrangement.spacedBy(BarGap),
            ) {
                UsageSegment(totals.cacheRead, cachedTint)
                UsageSegment(totals.freshInput, newTint)
                UsageSegment(totals.outTokens, outTint)
            }
            Spacer(Modifier.height(3.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Legend(cachedTint, R.string.stat_cached, countParts(totals.cacheRead))
                Spacer(Modifier.width(LegendGap))
                Legend(newTint, R.string.stat_new, countParts(totals.freshInput))
                Spacer(Modifier.width(LegendGap))
                Legend(outTint, R.string.stat_out, countParts(totals.outTokens))
                // The time is the fourth figure and the only one that is not a
                // fraction of the bar, so it is held at the far end where it
                // cannot be read as one of the three.
                Spacer(Modifier.weight(1f))
                if (elapsed > 0L) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(9.dp),
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        spanText(formatSpan(elapsed)),
                        style = legendStyle(),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/**
 * One part of the bar. Its width is its count, so the three parts share the bar
 * the way they share the prompt; a part with nothing in it is dropped rather
 * than drawn as a hairline nobody could see.
 */
@Composable
private fun RowScope.UsageSegment(tokens: Int, color: Color) {
    if (tokens <= 0) return
    Box(
        Modifier
            .weight(tokens.toFloat())
            .fillMaxHeight()
            .clip(RoundedCornerShape(BarGap))
            .background(color),
    )
}

/**
 * One named figure under the bar: its own shade of the bar's colour, then the
 * count and what it counts.
 */
@Composable
private fun Legend(dot: Color, @StringRes labelRes: Int, value: List<Pair<String, String>>) {
    Box(
        Modifier
            .size(4.dp)
            .clip(CircleShape)
            .background(dot),
    )
    Spacer(Modifier.width(5.dp))
    Text(
        legendText(value, stringResource(labelRes)),
        style = legendStyle(),
        maxLines = 1,
        softWrap = false,
    )
}

/**
 * The one size the legend and the total share: the harness draws the line at 26
 * px on a 2.8125 px/dp screen, which is 9.2sp, and the words around the figures
 * one step under it.
 */
@Composable
private fun legendStyle() =
    MaterialTheme.typography.labelSmall.copy(
        fontSize = 9.sp,
        lineHeight = 12.sp,
        fontFamily = FontFamily.Monospace,
    )

/**
 * A figure with the word it counts, as one string: the figure carries its own
 * scale (`19.3k`), the word follows in the dimmer reading.
 */
@Composable
private fun legendText(value: List<Pair<String, String>>, word: String): AnnotatedString {
    val bright = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    return buildAnnotatedString {
        value.forEach { part ->
            withStyle(
                SpanStyle(
                    color = bright,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                ),
            ) { append(part.first + part.second) }
        }
        append(" ")
        withStyle(SpanStyle(color = dim, fontSize = 8.sp, fontFamily = FontFamily.Monospace)) {
            append(word)
        }
    }
}

/**
 * A span of time as the total: the figures bright and monospaced so a session
 * that crosses a unit does not shift, the units dim because they are a scale,
 * not a reading.
 */
@Composable
private fun spanText(parts: List<Pair<String, String>>): AnnotatedString {
    val bright = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    return buildAnnotatedString {
        parts.forEachIndexed { index, part ->
            if (index > 0) {
                withStyle(
                    SpanStyle(
                        color = bright,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                    ),
                ) { append(" ") }
            }
            withStyle(
                SpanStyle(
                    color = bright,
                    fontSize = 9.sp,
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
