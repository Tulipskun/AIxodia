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
        cacheWrite = 0,
        reasoning = 0,
        output = 238,
        ratePerSecond = 12.0,
        millis = 19_000,
        model = "mimo-v2.5-free",
        clock = "19:28",
    )

    @Test
    fun `two lines hold the two pairs and then the turn`() {
        assertEquals(
            """
            in: 63524/0  out: 0/238
            12 t/s · mimo-v2.5-free · ⏱ 19s  19:28
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
        assertEquals(listOf("in: 63524/0", "out: 0/238"), openAi.lines(TokenField.DEFAULT).take(2))
    }

    @Test
    fun `a pair stays when either half has something in it`() {
        assertEquals("in: 0/40", openAi.copy(cacheRead = 0, cacheWrite = 40)
            .render(listOf(TokenField.Input)).lines().first())
    }

    @Test
    fun `a count the provider did not report is left out, not zeroed`() {
        // Anthropic reports no reasoning count, so the pair goes rather than
        // printing out: 0/238, which reads as a measurement.
        val anthropic = openAi.copy(cacheRead = 0, cacheWrite = 0)
        assertEquals(
            "out: 0/238\n12 t/s · mimo-v2.5-free · ⏱ 19s  19:28",
            anthropic.render(TokenField.DEFAULT),
        )
    }

    @Test
    fun `the order is the configured order inside one line`() {
        assertEquals(
            "in: 63524/0\nmimo-v2.5-free · 12 t/s · ⏱ 19s  19:28",
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
