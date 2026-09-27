package com.tulipskun.aixodia.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Answers come back as Markdown, and a phone is a bad place to show the source
// of it: `**bold**` and `- item` and ```fences``` have to read as a document,
// full width, with no card around it. This is a small renderer for the subset
// models actually write, tuned for one thing the usual libraries do not do well:
// the text arrives a few characters at a time, so every half-finished construct
// (an unclosed fence, a `**` with no partner yet) has to render as *something*
// rather than flicker or throw.

/** One block of a Markdown answer. */
private sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Code(val text: String, val language: String) : Block
    data class Quote(val text: String) : Block
    data class ItemList(val items: List<String>, val ordered: Boolean) : Block
    data class Rule(val text: String) : Block
    data class Paragraph(val text: String) : Block
}

private val RE_HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val RE_RULE = Regex("^\\s*([-*_])\\s*(\\1\\s*){2,}$")
private val RE_BULLET = Regex("^\\s*[-*+]\\s+(.*)$")
private val RE_ORDERED = Regex("^\\s*(\\d+)[.)]\\s+(.*)$")
private val RE_QUOTE = Regex("^\\s*>\\s?(.*)$")
private val RE_FENCE = Regex("^\\s*(?:```|~~~)\\s*(\\S*)")

/** Splits an answer into blocks. A fence that is still open at the end of the text is a block, not an error. */
private fun blocks(text: String): List<Block> {
    val lines = text.split('\n')
    val out = ArrayList<Block>()
    var i = 0
    val paragraph = StringBuilder()
    val quote = StringBuilder()
    val bullets = ArrayList<String>()
    val ordered = ArrayList<String>()

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            out.add(Block.Paragraph(paragraph.toString().trimEnd()))
            paragraph.setLength(0)
        }
    }
    fun flushQuote() {
        if (quote.isNotEmpty()) {
            out.add(Block.Quote(quote.toString().trimEnd()))
            quote.setLength(0)
        }
    }
    fun flushList() {
        if (bullets.isNotEmpty()) {
            out.add(Block.ItemList(ArrayList(bullets), ordered = false))
            bullets.clear()
        }
        if (ordered.isNotEmpty()) {
            out.add(Block.ItemList(ArrayList(ordered), ordered = true))
            ordered.clear()
        }
    }
    fun flushAll() {
        flushParagraph()
        flushQuote()
        flushList()
    }

    while (i < lines.size) {
        val line = lines[i].replace('\r', ' ')
        val fence = RE_FENCE.find(line)
        if (fence != null) {
            flushAll()
            val language = fence.groupValues[1]
            val body = StringBuilder()
            i++
            var closed = false
            while (i < lines.size) {
                val next = lines[i].replace('\r', ' ')
                if (RE_FENCE.matches(next) && next.trimStart().startsWith(fence.value.trimStart().take(2))) {
                    closed = true
                    i++
                    break
                }
                if (body.isNotEmpty()) body.append('\n')
                body.append(next)
                i++
            }
            out.add(Block.Code(body.toString().trimEnd('\n'), language))
            continue
        }
        if (line.isBlank()) {
            flushAll()
            i++
            continue
        }
        if (RE_RULE.matches(line)) {
            flushAll()
            out.add(Block.Rule(line))
            i++
            continue
        }
        RE_HEADING.find(line)?.let { m ->
            flushAll()
            out.add(Block.Heading(m.groupValues[1].length, m.groupValues[2]))
            i++
            return@let
        }
        if (line.startsWith("```") || line.startsWith("~~~")) {
            i++
            continue
        }
        RE_QUOTE.find(line)?.let { m ->
            flushParagraph()
            flushList()
            if (quote.isNotEmpty()) quote.append('\n')
            quote.append(m.groupValues[1])
            i++
            return@let
        }
        RE_BULLET.find(line)?.let { m ->
            flushParagraph()
            flushQuote()
            ordered.clear()
            bullets.add(m.groupValues[1])
            i++
            return@let
        }
        RE_ORDERED.find(line)?.let { m ->
            flushParagraph()
            flushQuote()
            bullets.clear()
            ordered.add(m.groupValues[2])
            i++
            return@let
        }
        // A plain line continues the paragraph, the quote or the list item it
        // is under, which is how models write hard-wrapped prose.
        when {
            quote.isNotEmpty() && bullets.isEmpty() && ordered.isEmpty() -> {
                if (quote.isNotEmpty() && !quote.endsWith("\n")) quote.append('\n')
                quote.append(line.trim())
            }
            bullets.isNotEmpty() && ordered.isEmpty() -> bullets[bullets.lastIndex] += " " + line.trim()
            ordered.isNotEmpty() && bullets.isEmpty() -> ordered[ordered.lastIndex] += " " + line.trim()
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line.trimEnd())
            }
        }
        i++
    }
    flushAll()
    return out
}

private val RE_BOLD = Regex("\\*\\*(.+?)\\*\\*", RegexOption.DOT_MATCHES_ALL)
private val RE_STRIKE = Regex("~~(.+?)~~", RegexOption.DOT_MATCHES_ALL)
private val RE_LINK = Regex("\\[([^\\]]+)]\\(([^)]+)\\)")
private val RE_INLINE_CODE = Regex("`([^`]+)`")
private val RE_ITALIC = Regex("(?<![*\\w])\\*([^*\\n]+)\\*(?!\\*)")

/**
 * Inline Markdown. Code spans are lifted out first so a `*` inside one is
 * literal, then the rest is styled in one pass over the text.
 */
private fun inline(text: String, code: SpanStyle, link: SpanStyle): AnnotatedString = buildAnnotatedString {
    var rest = text
    while (true) {
        val codeMatch = RE_INLINE_CODE.find(rest)
        val linkMatch = RE_LINK.find(rest)
        val next = listOfNotNull(codeMatch, linkMatch).minByOrNull { it.range.first }
        if (next == null) break
        appendInline(rest.substring(0, next.range.first))
        if (next === codeMatch) {
            pushStyle(code)
            append(next.groupValues[1])
            pop()
        } else {
            withStyle(link) { append(next.groupValues[1]) }
        }
        rest = rest.substring(next.range.last + 1)
    }
    appendInline(rest)
}

private fun AnnotatedString.Builder.appendInline(text: String) {
    var rest = text
    while (true) {
        val bold = RE_BOLD.find(rest)
        val strike = RE_STRIKE.find(rest)
        val italic = RE_ITALIC.find(rest)
        val next = listOfNotNull(bold, strike, italic).minByOrNull { it.range.first }
        if (next == null) {
            append(rest)
            return
        }
        append(rest.substring(0, next.range.first))
        withStyle(
            when (next) {
                bold -> SpanStyle(fontWeight = FontWeight.Bold)
                strike -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                else -> SpanStyle(fontStyle = FontStyle.Italic)
            }
        ) { append(next.groupValues[1]) }
        rest = rest.substring(next.range.last + 1)
    }
}

@Composable
private fun headingStyle(level: Int): TextStyle = when (level) {
    1 -> MaterialTheme.typography.headlineSmall
    2 -> MaterialTheme.typography.titleLarge
    3 -> MaterialTheme.typography.titleMedium
    4 -> MaterialTheme.typography.titleSmall
    else -> MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
}

/**
 * Draws an answer as Markdown across the full width of the thread. Plain text
 * (no Markdown in it at all) takes the body style unchanged, so a short reply
 * does not get extra paragraph spacing.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val parsed = remember(text) { blocks(text) }
    val codeStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        background = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
    val linkStyle = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
    )
    if (parsed.size <= 1 && parsed.firstOrNull() is Block.Paragraph) {
        Text(
            text = inline((parsed.first() as Block.Paragraph).text, codeStyle, linkStyle),
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            modifier = modifier.fillMaxWidth(),
        )
        return
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        parsed.forEach { block ->
            when (block) {
                is Block.Heading -> Text(
                    text = inline(block.text, codeStyle, linkStyle),
                    style = headingStyle(block.level),
                    color = color,
                    modifier = Modifier.fillMaxWidth().padding(top = if (block.level <= 2) 6.dp else 2.dp),
                )

                is Block.Paragraph -> Text(
                    text = inline(block.text, codeStyle, linkStyle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                    modifier = Modifier.fillMaxWidth(),
                )

                is Block.Quote -> Row(Modifier.fillMaxWidth().padding(start = 4.dp)) {
                    Spacer(
                        Modifier
                            .width(2.dp)
                            .height(20.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        text = inline(block.text, codeStyle, linkStyle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                is Block.ItemList -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    block.items.forEachIndexed { index, item ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                text = if (block.ordered) "${index + 1}." else "•",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(if (block.ordered) 22.dp else 14.dp),
                            )
                            Text(
                                text = inline(item, codeStyle, linkStyle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = color,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                is Block.Code -> Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(12.dp)
                        .horizontalScroll(rememberScrollState()),
                ) {
                    if (block.language.isNotBlank()) {
                        Text(
                            block.language,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        block.text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                is Block.Rule -> Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
    }
}
