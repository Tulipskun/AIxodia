package com.tulipskun.aixodia.ui.display

import java.util.Locale

/**
 * A token count, shortened once it stops being readable in full.
 *
 * Six digits is where counting stops working: 63808 is a number you can hold in
 * your head, 127125 is not. Past that the figure is written 63.8k, and the
 * decimal is always there so the column does not change width between 1.2k and
 * 47k — a footer whose numbers jump about as you read them is worse than one
 * with slightly imprecise figures.
 */
fun formatCount(n: Int): String = when {
    n < 1_000 -> n.toString()
    n < 1_000_000 -> String.format(Locale.US, "%.1fk", n / 1_000.0)
    n < 1_000_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    else -> String.format(Locale.US, "%.1fB", n / 1_000_000_000.0)
}

/** Whole seconds, or one decimal under ten, so a short turn is not "0s". */
fun formatSeconds(millis: Long): String = when {
    millis < 10_000 -> String.format(Locale.US, "%.1fs", millis / 1000.0)
    else -> "${millis / 1000}s"
}

/**
 * A span of time as the session strip writes it: figure/unit pairs, so the units
 * can be dimmed and the figures left bright.
 *
 * Under ten seconds the tenths are worth keeping — 7.4s is a measurement and 7s
 * is a rounding — and past that the strip counts in minutes and seconds, or in
 * hours and minutes once a session has been open that long.
 */
fun formatSpan(millis: Long): List<Pair<String, String>> {
    val seconds = millis.coerceAtLeast(0L) / 1000.0
    val whole = seconds.toInt()
    return when {
        seconds < 10.0 -> listOf(String.format(Locale.US, "%.1f", seconds) to "s")
        whole < 60 -> listOf(whole.toString() to "s")
        whole < 3600 -> listOf((whole / 60).toString() to "m", (whole % 60).toString() to "s")
        else -> listOf((whole / 3600).toString() to "h", ((whole % 3600) / 60).toString() to "m")
    }
}

/**
 * A count as the strip writes it: the figure, and the scale suffix when there is
 * one, as the two pieces [SessionStrip] dims differently.
 */
fun countParts(n: Int): List<Pair<String, String>> {
    val text = formatCount(n)
    val suffix = text.takeLastWhile { it.isLetter() }
    return listOf(text.dropLast(suffix.length) to suffix)
}

/**
 * How the footer of one turn is written.
 *
 * One line, because the footer is read at a glance between two answers and the
 * things it names are all answers to the same question — which turn was this.
 * The model names it, the rate says how fast it wrote, the time says how long
 * the turn took, and the wall clock at the far end says when it landed.
 *
 * The two token pairs are fields here too, but they are not on by default: a
 * turn's counts add up into the session strip above the composer, and a figure
 * already on screen should not be printed twice. A reader who wants them per
 * turn names them in settings and gets them back.
 *
 * Which of the parts appear, and in what order, is a list of [TokenField]
 * because providers differ: Anthropic reports no reasoning count at all, and a
 * zero there would read as a measurement rather than as "this provider does not
 * say".
 */
enum class TokenField(val aliases: List<String>) {
    /** `in: cacheRead`, with `/cacheWrite` only when the provider reports writes */
    Input(listOf("in", "input")),

    /** `out: output`, with `/reasoning` only when the provider reports thinking */
    Output(listOf("out", "output")),

    /** `32t/s` */
    Rate(listOf("rate", "tps", "t/s")),

    /** the model name on its own */
    Model(listOf("model")),

    /** the elapsed time and the wall clock, together */
    Time(listOf("time", "clock")),
    ;

    companion object {
        /**
         * What the footer line shows unless a reader says otherwise: which model
         * answered, how fast it wrote, how long the turn took and when it
         * landed. The token pairs are opt-in — see the class comment.
         */
        val DEFAULT: List<TokenField> = listOf(Model, Rate, Time)

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

    /**
     * The footer as the parts that have something to say, in the configured
     * order. A part is a list of pieces because the drawing paints them
     * differently: the figures are the reading and the words around them are the
     * scale the reading is in.
     */
    fun entries(fields: List<TokenField>): List<FooterEntry> = visible(fields).mapNotNull { field ->
        when (field) {
            TokenField.Input -> FooterEntry(
                field,
                listOf(
                    "in " to true,
                    formatCount(cacheRead) to false,
                    "/" to true,
                    formatCount(cacheWrite) to false,
                ),
            )
            TokenField.Output -> FooterEntry(
                field,
                if (reportsReasoning) {
                    listOf(
                        "out " to true,
                        formatCount(reasoning) to false,
                        "/" to true,
                        formatCount(output) to false,
                    )
                } else {
                    listOf("out " to true, formatCount(output) to false)
                },
            )
            TokenField.Rate -> FooterEntry(
                field,
                listOf(String.format(Locale.US, "%.1f", ratePerSecond) to false, " tok/s" to true),
            )
            TokenField.Model -> if (model.isBlank()) null else FooterEntry(field, listOf(model to false))
            // The elapsed time is the only piece the part itself carries; the wall
            // clock belongs to the whole line and the drawing puts it at the far
            // end, so it is asked for separately rather than being the last piece.
            TokenField.Time -> timingPieces().takeIf { it.isNotEmpty() }?.let { FooterEntry(field, it) }
        }
    }

    /** The wall clock this turn landed at, when the footer has one to show. */
    fun clockIfShown(fields: List<TokenField>): String =
        if (TokenField.Time in visible(fields)) clock else ""

    /**
     * The whole line as plain text: the parts in order, then the wall clock at
     * the end, which is where the drawing puts it too.
     */
    fun footerLine(fields: List<TokenField>): String =
        (entries(fields).map { it.text } + clockIfShown(fields))
            .filter { it.isNotBlank() }
            .joinToString(" \u00B7 ")

    /** The elapsed time, split so the unit can be dimmed. */
    private fun timingPieces(): List<Pair<String, Boolean>> {
        if (millis <= 0L) return emptyList()
        val seconds = formatSeconds(millis)
        val unit = seconds.takeLastWhile { it.isLetter() }
        return listOf(seconds.dropLast(unit.length) to false, unit to true)
    }
}

/**
 * One part of the footer line, split into pieces so the drawing can put an icon
 * in front of it and leave the unit behind: the figure is what was measured and
 * the words are the scale it was measured in.
 */
data class FooterEntry(
    val field: TokenField,
    /** Each piece and whether it is dim, which is everything that is not a figure. */
    val pieces: List<Pair<String, Boolean>>,
) {
    /** The part as one string, which is what a test wants to read. */
    val text: String get() = pieces.joinToString("") { it.first }
}

