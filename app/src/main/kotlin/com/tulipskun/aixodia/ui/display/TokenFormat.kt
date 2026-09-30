package com.tulipskun.aixodia.ui.display

import java.util.Locale

/** Whole seconds, or one decimal under ten, so a short turn is not "0s". */
fun formatSeconds(millis: Long): String = when {
    millis < 10_000 -> String.format(Locale.US, "%.1fs", millis / 1000.0)
    else -> "${millis / 1000}s"
}

/**
 * How the token footer is written, and which lines it has.
 *
 * The shape is one item per line rather than a run of middots, because these
 * are four different things being said — a split of the prompt, a split of the
 * answer, a rate and a model — and a single line forces the reader to hold all
 * of it to find the one number they wanted.
 *
 * The pairs are the two numbers that cost something different and so belong
 * together: `in` is what was read from cache over what was written into it, and
 * `out` is what the model spent thinking over what it actually said. Both are
 * read as "first/second" with no label, which is why the label names the pair
 * and not the halves.
 *
 * Which lines appear, and in what order, is a list of [TokenField] rather than
 * a fixed string because providers differ: Anthropic reports no reasoning count
 * at all, and a reasoning line there would print a zero that reads as a
 * measurement when it means "this provider does not say".
 */
enum class TokenField(val label: String) {
    Input("in"),
    Output("out"),
    Rate("rate"),
    Model("model"),
    Time("time"),
    ;

    // The clock is not a field: it belongs to the message it sits beside, and a
    // question wears it inside its own bubble while an answer carries it in the
    // strip above. Time is the elapsed length of the turn, which is a different
    // thing and is what the footer is for.

    companion object {
        /**
         * What to show when nothing is configured. The four lines that are
         * always true of a turn, in the order a reader wants them.
         */
        val DEFAULT: List<TokenField> = listOf(Input, Output, Rate, Model, Time)

        /**
         * Both spellings resolve: the enum name (`Output`) and the label a
         * reader sees in the footer (`out`). The name is tried first because the
         * labels are short and would collide on case.
         */
        fun parse(spec: String): List<TokenField> =
            spec.split(',', ' ', '|', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { token ->
                    entries.firstOrNull { it.name.equals(token, ignoreCase = true) }
                        ?: entries.firstOrNull { it.label == token }
                }
                .distinct()
                .ifEmpty { DEFAULT }

        /** A spec stores field names, never labels: labels may be translated. */
        fun render(fields: List<TokenField>): String =
            fields.joinToString(",") { it.name }
    }
}

/**
 * The counts a footer is built from, already split the way the two prompts and
 * the answer split.
 */
data class TokenCounts(
    val cacheRead: Int = 0,
    val cacheWrite: Int = 0,
    val reasoning: Int = 0,
    val output: Int = 0,
    val ratePerSecond: Double = 0.0,
    val millis: Long = 0L,
    val model: String = "",
    /** The wall clock of the turn, or blank when the message never carried one. */
    val clock: String = "",
) {
    /**
     * The lines that carry something worth printing. A count the provider did
     * not report leaves its line out entirely rather than printing a zero, and
     * a pair only stays when one of its halves has something in it.
     */
    fun visible(fields: List<TokenField>): List<TokenField> = fields.filter { field ->
        when (field) {
            TokenField.Input -> cacheRead > 0 || cacheWrite > 0
            TokenField.Output -> output > 0 || reasoning > 0
            TokenField.Rate -> output > 0 && ratePerSecond > 0.0
            TokenField.Model -> model.isNotBlank()
            // A clock with no elapsed time still says when the turn happened, and
            // the other way round, so this line appears if either half is there.
            TokenField.Time -> millis > 0L || clock.isNotBlank()
        }
    }

    /** The footer lines, already formatted. Data, so a test can check the text. */
    fun lines(fields: List<TokenField>): List<String> = visible(fields).map { field ->
        when (field) {
            TokenField.Input -> "${field.label}: $cacheRead/$cacheWrite"
            TokenField.Output -> "${field.label}: $reasoning/$output"
            TokenField.Rate -> String.format(Locale.US, "%.0f t/s", ratePerSecond)
            TokenField.Model -> model
            // time used and the wall clock on one line: how long the turn took
            // and when it was asked, which is what you want when you are looking
            // for an answer you read this morning.
            // trimEnd, because the gap belongs between the two halves and there
            // is nothing to put after it when the clock is missing.
            TokenField.Time -> buildString {
                if (millis > 0L) append("\u23F1 ${formatSeconds(millis)}")
                if (clock.isNotBlank()) {
                    if (isNotEmpty()) append("  ")
                    append(clock)
                }
            }
        }
    }

    fun render(fields: List<TokenField>, separator: String = "\n"): String =
        lines(fields).joinToString(separator)
}
