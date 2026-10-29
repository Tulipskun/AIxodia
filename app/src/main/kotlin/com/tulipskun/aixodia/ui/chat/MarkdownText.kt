package com.tulipskun.aixodia.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
private val RE_FENCE = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")

/**
 * Splits an answer into blocks. A fence that is still open at the end of the text
 * is a block, not an error. This is the whole Markdown contract and it is pure,
 * so the tests drive it directly instead of rendering a screen.
 */
internal fun blocks(text: String): List<Block> {
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
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
        val line = lines[i]
        val fence = RE_FENCE.matchEntire(line)?.takeIf {
            // Backtick fences cannot contain backticks in their info string.
            // A literal inline run must stay prose, not disappear as a fence.
            it.groupValues[1].first() != '`' || !it.groupValues[2].contains('`')
        }
        if (fence != null) {
            flushAll()
            val marker = fence.groupValues[1]
            val language = fence.groupValues[2].trim().takeWhile { !it.isWhitespace() }
            val body = ArrayList<String>()
            i++
            while (i < lines.size) {
                val next = lines[i]
                val closing = RE_FENCE.matchEntire(next)
                if (closing != null && closing.groupValues[1].first() == marker.first() &&
                    closing.groupValues[1].length >= marker.length && closing.groupValues[2].isBlank()
                ) {
                    i++
                    break
                }
                body.add(next)
                i++
            }
            out.add(Block.Code(body.joinToString("\n"), language))
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
        // A header row followed by |---|---|: the row above it is the header.
        // Without this a table rendered as a pipe-separated paragraph, which is
        // not what the author wrote.
        if (isTableDivider(line) && paragraph.isNotEmpty()) {
            // The header is the last line of the paragraph in progress; anything
            // above it was prose and stays prose. The header has to be taken out
            // of the buffer before the flush, or it is emitted a second time as
            // a paragraph of its own.
            val linesSoFar = paragraph.toString().split('\n')
            val headerLine = linesSoFar.last()
            val before = linesSoFar.dropLast(1).filter { it.isNotBlank() }
            paragraph.setLength(0)
            flushAll()
            if (before.isNotEmpty()) out.add(Block.Paragraph(before.joinToString("\n")))

            val rows = mutableListOf<String>()
            var scanned = i + 1
            while (scanned < lines.size) {
                val next = lines[scanned]
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

/** Only unescaped pipes split cells or count as an outer table border. */
private fun splitTableRow(row: String): List<String> {
    val trimmed = row.trim()
    val cells = ArrayList<String>()
    val cell = StringBuilder()
    var backslashes = 0
    var endsWithSeparator = false
    for (char in trimmed) {
        endsWithSeparator = char == '|' && backslashes % 2 == 0
        if (endsWithSeparator) {
            cells.add(cell.toString().trim())
            cell.setLength(0)
        } else {
            cell.append(char)
        }
        backslashes = if (char == '\\') backslashes + 1 else 0
    }
    cells.add(cell.toString().trim())
    if (trimmed.startsWith('|')) cells.removeAt(0)
    if (endsWithSeparator && cells.isNotEmpty()) cells.removeAt(cells.lastIndex)
    return cells
}

/** The `| :-- | :-: | --: |` row carries the per-column alignment. */
private fun tableAligns(divider: String): List<TableAlign> =
    // GitHub's rule, which every renderer follows: a leading colon is left, a
    // trailing colon is right, both is centre. Note this is the opposite of
    // what `:---` looks like it should mean, which is why it is written out.
    splitTableRow(divider).map { c ->
        when {
            c.startsWith(":") && c.endsWith(":") -> TableAlign.Center
            c.startsWith(":") -> TableAlign.Start
            c.endsWith(":") -> TableAlign.End
            else -> TableAlign.Start
        }
    }

/** Splits a row on unescaped pipes and trims each cell. */
private fun tableCells(row: String): List<String> =
    splitTableRow(row).map { it.replace("\\|", "|") }

private val BOLD = SpanStyle(fontWeight = FontWeight.Bold)
private val BOLD_ITALIC = SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
private val ITALIC = SpanStyle(fontStyle = FontStyle.Italic)
private val STRIKE = SpanStyle(textDecoration = TextDecoration.LineThrough)

/**
 * Every inline construct in one alternation, so the earliest one in the text
 * wins whatever it is. Order inside the alternation is the tie-break:
 * `***x***` is matched by the first alternative or the outer pair would match
 * and leave a literal `*` behind.
 *
 * Code used to be lifted out in a separate pass before any emphasis was styled,
 * and that is what broke `**a `b` c**`: the code split the line in two and
 * neither half had a `**` partner left, so the markers were printed literally
 * while the code span still rendered. Code is just another alternative here, so
 * the bold match spans it and the code is styled inside.
 */
private val RE_INLINE = Regex(
    "`([^`]+)`" +
        "|\\[([^\\]]+)]\\(([^)]+)\\)" +
        "|\\*\\*\\*(.+?)\\*\\*\\*" +
        "|\\*\\*(.+?)\\*\\*" +
        "|~~(.+?)~~" +
        // An italic pair has to close on a real character on both sides, or
        // every `1 * 2 * 3` and every lone asterisk turns half the answer into
        // emphasis. That is the rule every Markdown implementation follows.
        "|(?<![*\\w])\\*([^*\\s](?:[^*\\n]*[^*\\s])?)\\*",
    RegexOption.DOT_MATCHES_ALL,
)

/**
 * Inline Markdown over one block of text. Internal rather than private so the
 * tests can assert on the spans: block parsing can tell you a line was read as
 * a paragraph, and never that the `**` in it actually came out bold.
 */
internal fun inline(text: String, code: SpanStyle, link: SpanStyle): AnnotatedString =
    buildAnnotatedString { renderInline(text, code, link) }

/**
 * [inline] once per block of text rather than once per recomposition.
 *
 * Styling a block is a regex walk over the whole of it, and a thread that does
 * it on every recomposition pays for it again and again while an answer streams:
 * the clock that drives the elapsed-time readouts recomposes the list several
 * times a second, and each pass restyled every block on screen, finished rows
 * included. The two styles are keys too, so a theme change restyles instead of
 * serving yesterday's colours out of the cache.
 */
@Composable
internal fun rememberInline(text: String, code: SpanStyle, link: SpanStyle): AnnotatedString =
    remember(text, code, link) { inline(text, code, link) }

private fun AnnotatedString.Builder.renderInline(text: String, code: SpanStyle, link: SpanStyle) {
    var rest = text
    while (true) {
        val match = RE_INLINE.find(rest)
        if (match == null) {
            append(rest)
            return
        }
        append(rest.substring(0, match.range.first))
        val g = match.groups
        when {
            // A code span is literal: nothing inside it is Markdown.
            g[1] != null -> withStyle(code) { append(g[1]!!.value) }
            // A link label and an emphasised run are Markdown in their own
            // right, so `**[read](url)**` keeps both the colour and the bold.
            g[2] != null -> withStyle(link) { renderInline(g[2]!!.value, code, link) }
            g[4] != null -> withStyle(BOLD_ITALIC) { renderInline(g[4]!!.value, code, link) }
            g[5] != null -> withStyle(BOLD) { renderInline(g[5]!!.value, code, link) }
            g[6] != null -> withStyle(STRIKE) { renderInline(g[6]!!.value, code, link) }
            else -> withStyle(ITALIC) { renderInline(g[7]!!.value, code, link) }
        }
        rest = rest.substring(match.range.last + 1)
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
    fillWidth: Boolean = true,
) {
    val parsed = remember(text) { blocks(text) }
    // Inline code reads as a quiet pill, not a black box: a name the user is
    // meant to notice, not one that competes with the sentence around it.
    // The size comes from the theme rather than a literal, so a text-scale change
    // or a font swap moves the code with the sentence around it.
    // They are built once per theme, because the styled blocks below are cached
    // against these instances: a new one per recomposition would miss every
    // cache entry and restyle the page for nothing.
    val typography = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    val codeStyle = remember(
        typography.bodySmall.fontSize,
        colors.onSurfaceVariant,
        colors.surfaceContainerHigh,
    ) {
        SpanStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = typography.bodySmall.fontSize,
            color = colors.onSurfaceVariant,
            background = colors.surfaceContainerHigh,
        )
    }
    val linkStyle = remember(colors.primary) {
        SpanStyle(
            color = colors.primary,
            textDecoration = TextDecoration.Underline,
        )
    }
    if (parsed.size <= 1 && parsed.firstOrNull() is Block.Paragraph) {
        Text(
            text = rememberInline((parsed.first() as Block.Paragraph).text, codeStyle, linkStyle),
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            modifier = if (fillWidth) modifier.fillMaxWidth() else modifier,
        )
        return
    }
    Column(if (fillWidth) modifier.fillMaxWidth() else modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        parsed.forEach { block ->
            when (block) {
                is Block.Heading -> Text(
                    text = rememberInline(block.text, codeStyle, linkStyle),
                    style = headingStyle(block.level),
                    color = color,
                    modifier = (if (fillWidth) Modifier.fillMaxWidth() else Modifier).padding(top = if (block.level <= 2) 6.dp else 2.dp),
                )

                is Block.Paragraph -> Text(
                    text = rememberInline(block.text, codeStyle, linkStyle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                    modifier = if (fillWidth) Modifier.fillMaxWidth() else Modifier,
                )

                is Block.Quote -> Row((if (fillWidth) Modifier.fillMaxWidth() else Modifier).padding(start = 4.dp)) {
                    Spacer(
                        Modifier
                            .width(2.dp)
                            .height(20.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        text = rememberInline(block.text, codeStyle, linkStyle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = if (fillWidth) Modifier.fillMaxWidth() else Modifier,
                    )
                }

                is Block.ItemList -> Column(if (fillWidth) Modifier.fillMaxWidth() else Modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    block.items.forEachIndexed { index, item ->
                        Row(if (fillWidth) Modifier.fillMaxWidth() else Modifier) {
                            Text(
                                text = if (block.ordered) "${index + 1}." else "•",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(if (block.ordered) 22.dp else 14.dp),
                            )
                            Text(
                                text = rememberInline(item, codeStyle, linkStyle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = color,
                                modifier = if (fillWidth) Modifier.fillMaxWidth() else Modifier,
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

                is Block.Table -> TableBlock(block, codeStyle, linkStyle, color, fillWidth)
            }
        }
    }
}


/**
 * A real table, and only as wide as it needs to be.
 *
 * The first cut filled the thread width and gave every cell a share by
 * character count, which turned a two-column file list into two cells marooned
 * at opposite ends of the screen with a canyon of nothing between them. Now the
 * table is measured: every column takes the width of its widest cell, and the
 * table shrinks to the sum. Only when that sum cannot fit the thread does it
 * scroll sideways, which is the case where sideways actually is the answer.
 */
@Composable
private fun TableBlock(
    table: Block.Table,
    codeStyle: SpanStyle,
    linkStyle: SpanStyle,
    color: Color,
    fillWidth: Boolean = true,
) {
    val columns = table.headerRow.size
    if (columns == 0) return
    val cellGap = 20.dp
    val widths = List(columns) { column ->
        val longest = (table.headerRow.getOrElse(column) { "" } to table.rows)
            .let { (header, rows) -> listOf(header) + rows.map { it.getOrElse(column) { "" } } }
            .maxOf { it.length }
        // A cell is allowed to wrap, but not to vanish: below this a one-word
        // column is unreadable.
        (longest.coerceAtLeast(4).coerceAtMost(28) * 8).dp
    }
    val sidePadding = 10.dp
    val natural = tableNaturalWidth(widths, cellGap, sidePadding)
    BoxWithConstraints(if (fillWidth) Modifier.fillMaxWidth() else Modifier) {
        val available = maxWidth
        val scroll = natural > available
        Box(
            Modifier
                .width(minOf(natural, available))
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                // Sideways only, and only when the table genuinely cannot fit:
                // a table that already fits must not invite a gesture that does
                // nothing. There is deliberately no verticalScroll here — the
                // thread is already the vertical scroller, and nesting one
                // inside the other crashes the app on the first frame.
                .horizontalScroll(rememberScrollState(), enabled = scroll),
        ) {
            // Width belongs inside the scrolling viewport. Constraining this
            // content to the screen first clipped the final columns permanently.
            Column(Modifier.width(natural)) {
                TableRow(
                    cells = table.headerRow,
                    widths = widths,
                    cellGap = cellGap,
                    sidePadding = sidePadding,
                    aligns = table.aligns,
                    codeStyle = codeStyle,
                    linkStyle = linkStyle,
                    color = color,
                    header = true,
                )
                table.rows.forEach { row ->
                    TableRow(
                        cells = row,
                        widths = widths,
                        cellGap = cellGap,
                        sidePadding = sidePadding,
                        aligns = table.aligns,
                        codeStyle = codeStyle,
                        linkStyle = linkStyle,
                        color = color,
                        header = false,
                    )
                }
            }
        }
    }
}

/** The content width includes both borders and every inter-column gap. */
internal fun tableNaturalWidth(widths: List<Dp>, cellGap: Dp, sidePadding: Dp): Dp =
    if (widths.isEmpty()) 0.dp else
        widths.fold(sidePadding * 2) { total, width -> total + width } + cellGap * (widths.size - 1)

/** One line of the grid: the header on a raised background, a rule under each row. */
@Composable
private fun TableRow(
    cells: List<String>,
    widths: List<Dp>,
    cellGap: Dp,
    sidePadding: Dp,
    aligns: List<TableAlign>,
    codeStyle: SpanStyle,
    linkStyle: SpanStyle,
    color: Color,
    header: Boolean,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (header) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
                else Modifier,
            )
            .padding(horizontal = sidePadding, vertical = 6.dp),
    ) {
        widths.forEachIndexed { index, width ->
            val align = aligns.getOrElse(index) { TableAlign.Start }
            Box(
                Modifier.width(width),
                contentAlignment = when (align) {
                    TableAlign.Start -> Alignment.CenterStart
                    TableAlign.Center -> Alignment.Center
                    TableAlign.End -> Alignment.CenterEnd
                },
            ) {
                Text(
                    text = rememberInline(cells.getOrElse(index) { "" }, codeStyle, linkStyle),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (header) color else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (index != widths.lastIndex) Spacer(Modifier.width(cellGap))
        }
    }
    if (!header) {
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        )
    }
}
