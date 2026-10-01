package com.tulipskun.aixodia.ui.display

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The footer text is data, not drawing, so it can be checked without a screen.
 * These lock the two lines that were asked for: the two token pairs on the
 * first, the turn's identity on the second, and no prompt total anywhere.
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

    @Test
    fun `the speed sits with the counts and not with the model`() {
        // A rate is a count per second, so it belongs on the line with the
        // counts; the model and the clock are about which turn this was.
        assertEquals(
            "in: 1/0  out: 0/1  7t/s\nmodel-x · ⏱ 1.0s  09:00",
            openAi.copy(cacheRead = 1, cacheWrite = 0, reasoning = 0, output = 1, ratePerSecond = 7.0, millis = 1_000, model = "model-x", clock = "09:00")
                .render(TokenField.DEFAULT),
        )
    }

    @Test
    fun `two lines hold the two pairs and then the turn`() {
        assertEquals(
            """
            in: 63524/277  out: 0/238  12 t/s
            mimo-v2.5-free · ⏱ 19s  19:28
            """.trimIndent(),
            openAi.render(TokenField.DEFAULT),
        )
    }

    @Test
    fun `the prompt total is nowhere in the footer text`() {
        // It was a third number and a reader never asked for it: the sum of the
        // halves is the provider's figure, not something the app measures.
        val text = openAi.render(TokenField.DEFAULT)
        assertEquals(false, text.contains("127125"))
        assertEquals(listOf("in: 63524/277", "out: 0/238"), openAi.copy(cacheWrite = 277).lines(TokenField.DEFAULT).take(2))
    }

    @Test
    fun `a pair stays when either half has something in it`() {
        assertEquals("in: 0/40", openAi.copy(cacheRead = 0, cacheWrite = 40)
            .render(listOf(TokenField.Input)).lines().first())
    }

    @Test
    fun `the reasoning half drops only where the provider does not count it`() {
        assertEquals("out: 0/238", openAi.render(listOf(TokenField.Output)).lines().first())
        // Anthropic's usage has no thinking count, so that half goes rather than
        // printing a zero that would read as a measurement.
        assertEquals(
            "out: 238",
            openAi.copy(reportsReasoning = false).render(listOf(TokenField.Output)).lines().first(),
        )
    }

    @Test
    fun `the cache pair is the two halves, not the total`() {
        // The second half is what the cache could not serve, so the two figures
        // add up to the prompt rather than one of them being the prompt.
        assertEquals("in: 63524/277", openAi.render(listOf(TokenField.Input)).lines().first())
        assertEquals("in: 0/1926", openAi.copy(cacheRead = 0, cacheWrite = 1926)
            .render(listOf(TokenField.Input)).lines().first())
    }

    @Test
    fun `the provider on screen drops only the half it does not count`() {
        assertEquals(
            "in: 2112/157  out: 0/605  32t/s\nmimo-v2.5-free · ⏱ 18s  19:33",
            openAi.copy(cacheRead = 2112, cacheWrite = 157, output = 605, ratePerSecond = 32.0,
                    millis = 18_000, clock = "19:33").render(TokenField.DEFAULT),
        )
    }

    @Test
    fun `a count the provider did not report is left out, not zeroed`() {
        // A turn that cached nothing shows no in: line at all rather than a pair
        // of zeroes, which would read as two measurements.
        val uncached = openAi.copy(cacheRead = 0, cacheWrite = 0)
        assertEquals(
            "out: 0/238  12 t/s\nmimo-v2.5-free · ⏱ 19s  19:28",
            uncached.render(TokenField.DEFAULT),
        )
    }

    @Test
    fun `the order is the configured order inside one line`() {
        assertEquals(
            "in: 63524/277  12 t/s\nmimo-v2.5-free · ⏱ 19s  19:28",
            openAi.render(listOf(TokenField.Input, TokenField.Model, TokenField.Rate, TokenField.Time)),
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
    fun `the elapsed time and the wall clock share one part`() {
        assertEquals("⏱ 19s  19:28", openAi.render(listOf(TokenField.Time)).lines().first())
        // Either half alone still keeps the part.
        assertEquals("⏱ 19s", openAi.copy(clock = "").render(listOf(TokenField.Time)).lines().first())
        assertEquals("19:28", openAi.copy(millis = 0L).render(listOf(TokenField.Time)).lines().first())
    }

    @Test
    fun `a turn with no counts at all renders nothing rather than a blank line`() {
        assertEquals("", TokenCounts().render(TokenField.DEFAULT))
    }
}
