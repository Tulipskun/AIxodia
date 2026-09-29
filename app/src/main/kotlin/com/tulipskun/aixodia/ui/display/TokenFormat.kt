package com.tulipskun.aixodia.ui.display

/** Whole seconds, or one decimal under ten, so a short turn is not "0s". */
fun formatSeconds(millis: Long): String = when {
    millis < 10_000 -> String.format(java.util.Locale.US, "%.1fs", millis / 1000.0)
    else -> "${millis / 1000}s"
}

/**
 * How the token footer is written, and which counts it shows.
 *
 * The labels are deliberately the ones every developer already reads: `in`,
 * `out`, `cache`, `t/s`. A footer is glanced at between two answers, and a
 * sentence like "อ่านใหม่" asks the reader to stop and translate. Each count is
 * written `part/total`, which is self-explanatory: in 63875/127683 says 63875
 * of the prompt was fresh and 127683 was the whole thing, with no sentence
 * needed.
 *
 * Which counts appear, and in what order, is a list of [TokenField] rather than
 * a fixed string, because providers differ. Anthropic reports no reasoning
 * count at all, so a `think` field there would show a zero that means "this
 * provider does not say" rather than "the model did not think". A per-provider
 * list keeps the footer honest without every reader having to know that.
 */
enum class TokenField(val label: String) {
    Input("in"),
    Output("out"),
    CacheRead("cache"),
    CacheWrite("cache↑"),
    Reasoning("think"),
    Rate("rate"),
    Time("time"),
    ;

    companion object {
        /**
         * The middle dot with a space either side. Named because a separator
         * typed straight into a default argument is unreadable in a diff.
         */
        const val SEPARATOR: String = " \u00B7 "

        /**
         * What to show when nothing configured: the prompt split, the answer,
         * the speed and the time. Cache and reasoning are left out unless the
         * provider actually reported them, which [visible] decides.
         */
        val DEFAULT: List<TokenField> = listOf(Input, Output, Rate, Time)

        /**
         * Parses a stored setting. Unknown names are dropped rather than
         * failing, and unusable input falls back to [DEFAULT] here rather than at
         * each call site, so a typo in the preference cannot blank the footer no
         * matter who reads it.
         */
        fun parse(spec: String): List<TokenField> =
            spec.split(',', ' ', '|')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                // Both spellings work: the enum name (`CacheRead`) and the label
                // a reader sees in the footer (`cache`). The labels are two
                // characters and collide on case, so the name is tried first and
                // an exact label match only after it.
                .mapNotNull { token ->
                    entries.firstOrNull { it.name.equals(token, ignoreCase = true) }
                        ?: entries.firstOrNull { it.label == token }
                }
                .distinct()
                .ifEmpty { DEFAULT }

        /**
         * A spec round-trips: what is stored is the field name, never the label,
         * because the label is display text and may be translated later.
         */
        fun render(fields: List<TokenField>): String =
            fields.joinToString(",") { it.name }
    }
}

/**
 * The counts a footer can be built from, already split by the provider's own
 * convention. `input` is a pair rather than a single number precisely because
 * the two conventions exist: `fresh` is what was not cached, `total` is the
 * whole prompt.
 */
data class TokenCounts(
    val inputFresh: Int = 0,
    val inputTotal: Int = 0,
    val outputAnswer: Int = 0,
    val outputTotal: Int = 0,
    val cacheRead: Int = 0,
    val cacheWrite: Int = 0,
    val reasoning: Int = 0,
    val ratePerSecond: Double = 0.0,
    val millis: Long = 0L,
) {
    /**
     * The fields that carry something worth printing. A count the provider did
     * not report is absent from the row rather than printed as zero, because a
     * zero reads as a measurement.
     */
    fun visible(fields: List<TokenField>): List<TokenField> = fields.filter { field ->
        when (field) {
            TokenField.Input -> inputFresh > 0 || inputTotal > inputFresh
            TokenField.Output -> outputAnswer > 0
            TokenField.CacheRead -> cacheRead > 0
            TokenField.CacheWrite -> cacheWrite > 0
            TokenField.Reasoning -> reasoning > 0
            TokenField.Rate -> outputAnswer > 0 && ratePerSecond > 0.0
            TokenField.Time -> millis > 0L
        }
    }

    /**
     * The footer, as a list of already-labelled pieces. Kept as data so the
     * drawing layer has no format knowledge and a test can check the text
     * without a screen.
     */
    fun parts(fields: List<TokenField>): List<String> = visible(fields).map { field ->
        when (field) {
            // in: fresh/total — the split is the whole point of the format.
            TokenField.Input -> "${field.label}: $inputFresh/$inputTotal"
            TokenField.Output -> "${field.label}: $outputAnswer/$outputTotal"
            TokenField.CacheRead -> "${field.label} ${cacheRead}"
            TokenField.CacheWrite -> "${field.label} ${cacheWrite}"
            TokenField.Reasoning -> "${field.label} ${reasoning}"
            TokenField.Rate -> String.format(java.util.Locale.US, "%.0f t/s", ratePerSecond)
            TokenField.Time -> "\u23F1 ${formatSeconds(millis)}"
        }
    }

    fun render(fields: List<TokenField>, separator: String = TokenField.SEPARATOR): String =
        parts(fields).joinToString(separator)
}
