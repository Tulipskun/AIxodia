package com.tulipskun.aixodia.ui.display

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The footer text is data, not drawing, so it can be checked without a screen.
 * These lock the one line the footer became: the model, the speed, the elapsed
 * time and the wall clock — and none of the token pairs, which moved up into the
 * session strip where a whole session's worth of them adds up.
 */
class TokenFormatTest {

    private val openAi = TokenCounts(
        cacheRead = 63524,
        cacheWrite = 277,
        reportsReasoning = true,
        reasoning = 0,
        output = 238,
        ratePerSecond = 12.0,
        millis = 19_000,
        model = "mimo-v2.5-free",
        clock = "19:28",
    )

    /** The line as plain text, which is what these tests are about. */
    private fun line(counts: TokenCounts, fields: List<TokenField> = TokenField.DEFAULT) =
        counts.footerLine(fields)

    @Test
    fun `the default names the turn and leaves the pairs to the strip`() {
        // The counts of a turn add up into the session strip, so printing them
        // again under every answer would be the same figure twice.
        assertEquals(
            "mimo-v2.5-free · 12.0 tok/s · 19s · 19:28",
            line(openAi),
        )
    }

    @Test
    fun `the pairs come back when a reader asks for them`() {
        assertEquals(
            "in 63.5k/277 · out 0/238 · 12.0 tok/s · mimo-v2.5-free · 19s · 19:28",
            line(openAi, listOf(TokenField.Input, TokenField.Output, TokenField.Rate, TokenField.Model, TokenField.Time)),
        )
    }

    @Test
    fun `a pair stays when either half has something in it`() {
        assertEquals("in 0/40", line(openAi.copy(cacheRead = 0, cacheWrite = 40), listOf(TokenField.Input)))
    }

    @Test
    fun `a count the provider did not report is left out, not zeroed`() {
        // A turn that cached nothing shows no `in` part at all rather than a pair
        // of zeroes, which would read as two measurements.
        val uncached = openAi.copy(cacheRead = 0, cacheWrite = 0)
        assertEquals(
            "out 0/238 · 12.0 tok/s · mimo-v2.5-free · 19s · 19:28",
            line(uncached, listOf(TokenField.Input, TokenField.Output, TokenField.Rate, TokenField.Model, TokenField.Time)),
        )
    }

    @Test
    fun `the reasoning half drops only where the provider does not count it`() {
        assertEquals("out 0/238", line(openAi, listOf(TokenField.Output)))
        // Anthropic's usage has no thinking count, so that half goes rather than
        // printing a zero that would read as a measurement.
        assertEquals("out 238", line(openAi.copy(reportsReasoning = false), listOf(TokenField.Output)))
    }

    @Test
    fun `the cache pair is the two halves, not the total`() {
        // The second half is what the cache could not serve, so the two figures
        // add up to the prompt rather than one of them being the prompt.
        assertEquals("in 63.5k/277", line(openAi, listOf(TokenField.Input)))
        assertEquals("in 0/1.9k", line(openAi.copy(cacheRead = 0, cacheWrite = 1926), listOf(TokenField.Input)))
    }

    @Test
    fun `the order is the configured order`() {
        assertEquals(
            "in 63.5k/277 · 12.0 tok/s · mimo-v2.5-free · 19s · 19:28",
            line(openAi, listOf(TokenField.Input, TokenField.Rate, TokenField.Model, TokenField.Time)),
        )
    }

    @Test
    fun `the elapsed time and the wall clock are separate parts`() {
        // The clock is asked for apart from the elapsed time because the drawing
        // puts it at the far end of the line, not beside the seconds.
        assertEquals(listOf("19s"), openAi.entries(listOf(TokenField.Time)).map { it.text })
        assertEquals("19:28", openAi.clockIfShown(listOf(TokenField.Time)))
        assertEquals("19s · 19:28", line(openAi, listOf(TokenField.Time)))
        // Either half alone still keeps the part.
        assertEquals("19s", line(openAi.copy(clock = ""), listOf(TokenField.Time)))
        assertEquals("19:28", line(openAi.copy(millis = 0L), listOf(TokenField.Time)))
    }

    @Test
    fun `the rate keeps a decimal because that is the figure that moves`() {
        assertEquals(
            "52.3 tok/s",
            openAi.copy(output = 12, ratePerSecond = 52.34).entries(listOf(TokenField.Rate)).first().text,
        )
    }

    @Test
    fun `a turn with no counts at all renders nothing rather than a blank line`() {
        assertEquals("", line(TokenCounts()))
    }

    @Test
    fun `a figure is bright and its unit is dim`() {
        // The drawing paints the pieces differently: the numbers are the reading,
        // the words around them are the scale they are in.
        assertEquals(
            listOf("in " to true, "63.5k" to false, "/" to true, "277" to false),
            openAi.entries(listOf(TokenField.Input)).first().pieces,
        )
    }

    @Test
    fun `a spec round-trips through the preference string`() {
        val spec = "Input,Output,Rate,Model,Time"
        assertEquals(spec, TokenField.render(TokenField.parse(spec)))
        assertEquals(spec, TokenField.render(TokenField.parse("input,output,rate,model,time")))
        // And the short forms the footer itself shows.
        assertEquals(spec, TokenField.render(TokenField.parse("in,out,rate,model,time")))
    }

    @Test
    fun `a hand-edited spec with a typo keeps the fields it did name`() {
        assertEquals(
            listOf(TokenField.Input, TokenField.Output, TokenField.Rate),
            TokenField.parse("in, output, nonsense, rate"),
        )
        // Nothing usable means the default, so a typo cannot blank the footer.
        assertEquals(TokenField.DEFAULT, TokenField.parse("   "))
    }

    @Test
    fun `a count stays whole until it stops being readable`() {
        // Six digits is where counting stops working in your head.
        assertEquals("0", formatCount(0))
        assertEquals("999", formatCount(999))
        assertEquals("1.0k", formatCount(1_000))
        assertEquals("1.2k", formatCount(1_234))
        assertEquals("63.8k", formatCount(63_808))
        assertEquals("638.1k", formatCount(638_075))
        assertEquals("1.0M", formatCount(1_000_000))
        assertEquals("2.5M", formatCount(2_450_000))
        assertEquals("1.0B", formatCount(1_000_000_000))
    }

    @Test
    fun `the decimal is always there so the figures do not change width`() {
        // 47k and 1.2k side by side would make the line jump about as it is
        // read; 47.0k and 1.2k do not.
        assertEquals("1.2k", formatCount(1_200))
        assertEquals("47.0k", formatCount(47_000))
    }

    @Test
    fun `a span under a minute is seconds and past it is minutes`() {
        // The strip's figures, which are the ones that can run long enough to
        // need two units.
        assertEquals(listOf("7.4" to "s"), formatSpan(7_400))
        assertEquals(listOf("45" to "s"), formatSpan(45_200))
        assertEquals(listOf("12" to "m", "30" to "s"), formatSpan(750_000))
        assertEquals(listOf("1" to "h", "5" to "m"), formatSpan(3_900_000))
    }

    @Test
    fun `a count hands its scale back as the unit the strip dims`() {
        assertEquals(listOf("24.8" to "k"), countParts(24_800))
        assertEquals(listOf("512" to ""), countParts(512))
    }
}
