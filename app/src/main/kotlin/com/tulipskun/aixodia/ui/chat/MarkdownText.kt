package com.tulipskun.aixodia.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
internal sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Code(val text: String, val language: String) : Block
    data class Quote(val text: String) : Block
    data class ItemList(val items: List<String>, val ordered: Boolean) : Block
    data class Rule(val text: String) : Block
    data class Paragraph(val text: String) : Block
    data class Table(
        val headerRow: List<String>,
        val rows: List<List<String>>,
        val aligns: List<TableAlign>,
    ) : Block
}

internal enum class TableAlign { Start, Center, End }

private val RE_HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val RE_RULE = Regex("^\\s*([-*_])\\s*(\\1\\s*){2,}$")
private val RE_BULLET = Regex("^\\s*[-*+]\\s+(.*)$")
private val RE_ORDERED = Regex("^\\s*(\\d+)[.)]\\s+(.*)$")
private val RE_QUOTE = Regex("^\\s*>\\s?(.*)$")
private val RE_FENCE = Regex("^\\s*(?:```|~~~)\\s*(\\S*)")

/**
 * Splits an answer into blocks. A fence that is still open at the end of the text
 * is a block, not an error. This is the whole Markdown contract and it is pure,
 * so the tests drive it directly instead of rendering a screen.
 */
internal fun blocks(text: String): List<Block> {
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
        // One line is one construct. Each branch below has to leave the loop
        // on its own: `return@let` only returns from the lambda, so a matched
        // line used to fall through to the continuation below and be appended
        // to the item it had just started — "1. read" became one item reading
        // "read 1. read", and the next line was consumed by the second `i++`
        // and lost. That is how an answer lost item 2 and repeated item 1.
        val heading = RE_HEADING.find(line)
        if (heading != null) {
            flushAll()
            out.add(Block.Heading(heading.groupValues[1].length, heading.groupValues[2]))
            i++
            continue
        }
        if (line.startsWith("```") || line.startsWith("~~~")) {
            i++
            continue
        }
        val quoted = RE_QUOTE.find(line)
        if (quoted != null) {
            flushParagraph()
            flushList()
            if (quote.isNotEmpty()) quote.append('\n')
            quote.append(quoted.groupValues[1])
            i++
            continue
        }
        val bullet = RE_BULLET.find(line)
        if (bullet != null) {
            flushParagraph()
            flushQuote()
            if (ordered.isNotEmpty()) flushList()
            bullets.add(bullet.groupValues[1])
            i++
            continue
        }
        val numbered = RE_ORDERED.find(line)
        if (numbered != null) {
            flushParagraph()
            flushQuote()
            if (bullets.isNotEmpty()) flushList()
            ordered.add(numbered.groupValues[2])
            i++
            continue
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
        // A header row followed by |---|---|: the row above it is the header.
        // Without this a table rendered as a pipe-separated paragraph, which is
        // not what the author wrote.
        if (isTableDivider(line) && paragraph.isNotEmpty()) {
            // The header is the last line of the paragraph in progress; anything
            // above it was prose and stays prose.
            val linesSoFar = paragraph.toString().split('\n')
            val headerLine = linesSoFar.last()
            val before = linesSoFar.dropLast(1).filter { it.isNotBlank() }
            flushAll()
            if (before.isNotEmpty()) out.add(Block.Paragraph(before.joinToString("\n")))

            val rows = mutableListOf<String>()
            var scanned = i + 1
            while (scanned < lines.size) {
                val next = lines[scanned].replace('\r', ' ')
                if (next.isBlank() || !next.contains('|') || isTableDivider(next)) break
                rows.add(next)
                scanned++
            }
            out.add(
                Block.Table(
                    headerRow = tableCells(headerLine),
                    rows = rows.map(::tableCells),
                    aligns = tableAligns(line),
                ),
            )
            i = scanned
            continue
        }
        i++
    }
    flushAll()
    return out
}

/**
 * A table divider is a row whose every cell is dashes and optional colons —
 * `| --- | :-: |`, or the same without the outer pipes. It has to be a real
 * test, not a pattern: a bare `---` is a horizontal rule and must stay one.
 */
private fun isTableDivider(line: String): Boolean {
    if (!line.contains('|')) return false
    val cells = splitTableRow(line)
    return cells.isNotEmpty() && cells.all { RE_CELL_ALIGN.matches(it) }
}

private val RE_CELL_ALIGN = Regex("^:?-{1,}:?$")

/** Splits a row on pipes, ignoring the empty cells an outer pipe leaves. */
private fun splitTableRow(row: String): List<String> {
    val trimmed = row.trim().removePrefix("|").removeSuffix("|")
    return trimmed.split('|').map { it.trim() }
}

/** The `| :-- | :-: | --: |` row carries the per-column alignment. */
private fun tableAligns(divider: String): List<TableAlign> =
    splitTableRow(divider).map { c ->
        when {
            c.startsWith(":") && c.endsWith(":") -> TableAlign.Center
            c.endsWith(":") -> TableAlign.End
            else -> TableAlign.Start
        }
    }

/** Splits a row on unescaped pipes and trims each cell. */
private fun tableCells(row: String): List<String> =
    splitTableRow(row).map { it.replace("\\|", "|") }

private val RE_BOLD = Regex("\\*\\*(.+?)\\*\\*", RegexOption.DOT_MATCHES_ALL)
private val RE_STRIKE = Regex("~~(.+?)~~", RegexOption.DOT_MATCHES_ALL)
private val RE_LINK = Regex("\\[([^\\]]+)]\\(([^)]+)\\)")
private val RE_INLINE_CODE = Regex("`([^`]+)`")
private val RE_ITALIC = Regex("(?<![*\\w])\\*([^*\\n]+)\\*(?!\\*)")
private val RE_BOLD_ITALIC = Regex("\\*\\*\\*(.+?)\\*\\*\\*", RegexOption.DOT_MATCHES_ALL)

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
            // The label is Markdown too, so `**[read](url)**` reads the way it
            // looks instead of dropping the emphasis.
            withStyle(link) { appendInline(next.groupValues[1]) }
        }
        rest = rest.substring(next.range.last + 1)
    }
    appendInline(rest)
}

private fun AnnotatedString.Builder.appendInline(text: String) {
    var rest = text
    while (true) {
        // `***x***` has to be tried before `**x**`, or the outer pair matches
        // and leaves a literal `*` at the end of the text.
        val boldItalic = RE_BOLD_ITALIC.find(rest)
        val bold = RE_BOLD.find(rest)
        val strike = RE_STRIKE.find(rest)
        val italic = RE_ITALIC.find(rest)
        val next = listOfNotNull(boldItalic, bold, strike, italic).minByOrNull { it.range.first }
        if (next == null) {
            append(rest)
            return
        }
        append(rest.substring(0, next.range.first))
        withStyle(
            when (next) {
                boldItalic -> SpanStyle(
                    fontWeight = FontWeight.Bold,
                    fontStyle = FontStyle.Italic,
                )
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
    // Inline code reads as a quiet pill, not a black box: a name the user is
    // meant to notice, not one that competes with the sentence around it.
    val codeStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        background = MaterialTheme.colorScheme.surfaceContainerHigh,
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

                is Block.Table -> TableBlock(block, codeStyle, linkStyle, color)
            }
        }
    }
}


/**
 * A real table: one row per line of cells, each column sized to the widest of
 * its own cells, header on its own row. Drawn as a grid rather than pipes, so a
 * three-column table stays readable on a phone instead of wrapping into soup.
 */
@Composable
private fun TableBlock(
    table: Block.Table,
    codeStyle: SpanStyle,
    linkStyle: SpanStyle,
    color: Color,
) {
    val columns = table.headerRow.size
    if (columns == 0) return
    val shape = RoundedCornerShape(6.dp)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = shape,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Column(Modifier.padding(vertical = 2.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                table.headerRow.forEachIndexed { index, cell ->
                    TableCell(cell, index, table.aligns, columns, codeStyle, linkStyle, color, bold = true)
                }
            }
            table.rows.forEach { row ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    for (index in 0 until columns) {
                        TableCell(
                            row.getOrElse(index) { "" },
                            index,
                            table.aligns,
                            columns,
                            codeStyle,
                            linkStyle,
                            color,
                            bold = false,
                        )
                    }
                }
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                )
            }
        }
    }
}

/**
 * One cell. The weight is the share of the widest cell in that column, so a
 * narrow "no" column does not take a third of the width just because a header
 * says "Note".
 */
@Composable
private fun RowScope.TableCell(
    text: String,
    index: Int,
    aligns: List<TableAlign>,
    columns: Int,
    codeStyle: SpanStyle,
    linkStyle: SpanStyle,
    color: Color,
    bold: Boolean,
) {
    val align = aligns.getOrElse(index) { TableAlign.Start }
    val weight = (text.length.coerceAtLeast(6).toFloat()).coerceAtMost(64f)
    Column(
        Modifier
            .weight(weight)
            .padding(end = if (index == columns - 1) 0.dp else 8.dp),
        horizontalAlignment = when (align) {
            TableAlign.Start -> Alignment.Start
            TableAlign.Center -> Alignment.CenterHorizontally
            TableAlign.End -> Alignment.End
        },
    ) {
        Text(
            text = inline(text, codeStyle, linkStyle),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            color = if (bold) color else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
