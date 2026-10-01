package com.tulipskun.aixodia.ui.display

import java.util.Locale

/** Whole seconds, or one decimal under ten, so a short turn is not "0s". */
fun formatSeconds(millis: Long): String = when {
    millis < 10_000 -> String.format(Locale.US, "%.1fs", millis / 1000.0)
    else -> "${millis / 1000}s"
}

/**
 * How the token footer is written.
 *
 * Two lines, because the footer answers two questions and they want different
 * amounts of attention. The first is what it cost and how fast: the prompt and
 * the answer, each split into the two halves that cost something different — the
 * cache read against the cache write, and the thinking against the words — with
 * the speed beside them, since a rate is a count per second and belongs with the
 * counts. The second is which turn this was: which model, how long it took, and
 * when it was asked. A single line of five figures forces the reader to hold all
 * of it to find the one they came for, and a line per figure means scrolling to
 * see what model answered.
 *
 * The prompt total is not shown. It was here earlier as a third number, and it
 * is the one a reader never asked for: what they want to know is what was
 * reused and what was generated, and the sum of those is the provider's number,
 * not the app's.
 *
 * Which of the five parts appear, and their order, is a list of [TokenField]
 * because providers differ: Anthropic reports no reasoning count at all, and a
 * zero there would read as a measurement rather than as "this provider does not
 * say".
 */
enum class TokenField(val aliases: List<String>) {
    /** `in: cacheRead`, with `/cacheWrite` only when the provider reports writes */
    Input(listOf("in", "input")),

    /** `out: output`, with `/reasoning` only when the provider reports thinking */
    Output(listOf("out", "output")),

    /** `12 t/s` */
    Rate(listOf("rate", "tps", "t/s")),

    /** the model name on its own */
    Model(listOf("model")),

    /** the elapsed time and the wall clock, together */
    Time(listOf("time", "clock")),
    ;

    companion object {
        val DEFAULT: List<TokenField> = listOf(Input, Output, Rate, Model, Time)

        /**
         * Every spelling a reader might type resolves: the enum name, the short
         * form the footer shows (`in`, `out`, `t/s`), and the word the field is
         * called. The names are tried first so a typo cannot collide with an
         * alias, and an unusable spec falls back to the default rather than
         * blanking the footer.
         */
        fun parse(spec: String): List<TokenField> =
            spec.split(',', ' ', '|', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { token ->
                    entries.firstOrNull { it.name.equals(token, ignoreCase = true) }
                        ?: entries.firstOrNull { entry -> entry.aliases.any { it.equals(token, ignoreCase = true) } }
                }
                .distinct()
                .ifEmpty { DEFAULT }

        /** A spec stores field names, never display text. */
        fun render(fields: List<TokenField>): String =
            fields.joinToString(",") { it.name }
    }
}

/** The counts the footer is built from, already split into the two halves. */
data class TokenCounts(
    val cacheRead: Int = 0,
    val cacheWrite: Int = 0,
    /**
     * Whether the provider counts reasoning separately. Anthropic's usage block
     * has no thinking count at all, so the half is dropped there; OpenAI and
     * Gemini report one, a genuine zero on a model that does not think.
     *
     * The cache pair never needs this: its second half is derived as the part of
     * the prompt the cache could not serve, which is the difference between the
     * prompt total and what was read from cache either way.
     */
    val reportsReasoning: Boolean = false,
    val reasoning: Int = 0,
    val output: Int = 0,
    val ratePerSecond: Double = 0.0,
    val millis: Long = 0L,
    val model: String = "",
    /** The wall clock of the turn, blank when the message never carried one. */
    val clock: String = "",
) {
    /**
     * A part that has nothing in it is left out rather than printed as a zero,
     * which would read as a measurement. A pair only stays when one of its
     * halves has something in it, and the time part stays if either the elapsed
     * time or the clock is there.
     */
    fun visible(fields: List<TokenField>): List<TokenField> = fields.filter { field ->
        when (field) {
            TokenField.Input -> cacheRead > 0 || cacheWrite > 0
            TokenField.Output -> output > 0 || reasoning > 0
            TokenField.Rate -> output > 0 && ratePerSecond > 0.0
            TokenField.Model -> model.isNotBlank()
            TokenField.Time -> millis > 0L || clock.isNotBlank()
        }
    }

    /** One line per part, already formatted. Data, so a test can check the text. */
    fun lines(fields: List<TokenField>): List<String> = visible(fields).map { field ->
        when (field) {
            TokenField.Input -> "in: $cacheRead/$cacheWrite"
            TokenField.Output ->
                if (reportsReasoning) "out: $reasoning/$output" else "out: $output"
            TokenField.Rate -> String.format(Locale.US, "%.0f t/s", ratePerSecond)
            TokenField.Model -> model
            // The gap belongs between the two halves and there is nothing to put
            // after it when the clock is missing.
            TokenField.Time -> buildString {
                if (millis > 0L) append("\u23F1 ${formatSeconds(millis)}")
                if (clock.isNotBlank()) {
                    if (isNotEmpty()) append("  ")
                    append(clock)
                }
            }
        }
    }

    /**
     * The two lines, split the way the footer draws them: the two token pairs on
     * the first with the speed beside them, then the model, the elapsed time and
     * the wall clock on the second. The speed belongs with the counts because it
     * is a count per second, and the rest belongs together because it is all
     * about which turn this was.
     *
     * The split is fixed rather than configured because it is the only
     * arrangement where the cost of a turn and the identity of a turn stay
     * legible together.
     */
    fun render(fields: List<TokenField>): String {
        val cost = lines(fields.filter { it == TokenField.Input || it == TokenField.Output || it == TokenField.Rate })
        val turn = lines(fields.filter { it == TokenField.Model || it == TokenField.Time })
        return listOf(
            cost.joinToString("  "),
            turn.joinToString(" \u00B7 "),
        ).filter { it.isNotBlank() }.joinToString("\n")
    }
}
