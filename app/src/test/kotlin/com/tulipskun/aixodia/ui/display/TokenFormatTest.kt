package com.tulipskun.aixodia.ui.display

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The footer text is data, not drawing, so it can be checked without a screen.
 * These lock the shape that was asked for: one item per line, the two pairs
 * written first/second, and a count nobody reported left out rather than zeroed.
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
    fun `the default is one item per line, in the order asked for`() {
        assertEquals(
            """
            in: 63524/0
            out: 0/238
            12 t/s
            mimo-v2.5-free
            ⏱ 19s  19:28
            """.trimIndent(),
            openAi.render(TokenField.DEFAULT),
        )
    }

    @Test
    fun `a pair stays when either half has something in it`() {
        val onlyWrite = openAi.copy(cacheRead = 0, cacheWrite = 40)
        assertEquals("in: 0/40", onlyWrite.render(listOf(TokenField.Input)))
    }

    @Test
    fun `a count the provider did not report leaves its line out`() {
        // Anthropic reports no reasoning count, so the line goes rather than
        // printing out: 0/238, which reads as a measurement.
        val anthropic = openAi.copy(cacheRead = 0, cacheWrite = 0)
        assertEquals(
            listOf("out: 0/238", "12 t/s", "mimo-v2.5-free", "⏱ 19s  19:28"),
            anthropic.lines(TokenField.DEFAULT),
        )
    }

    @Test
    fun `the order is the configured order, not the declaration order`() {
        assertEquals(
            listOf("⏱ 19s  19:28", "mimo-v2.5-free", "12 t/s"),
            openAi.lines(listOf(TokenField.Time, TokenField.Model, TokenField.Rate)),
        )
    }

    @Test
    fun `a spec round-trips through the preference string`() {
        val spec = "Input,Output,Rate,Model,Time"
        assertEquals(spec, TokenField.render(TokenField.parse(spec)))
        // And the short labels a reader would type resolve to the same fields.
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
    fun `reasoning is the first half of out because it is not words anyone read`() {
        val thinking = openAi.copy(reasoning = 28, output = 40)
        assertEquals("out: 28/40", thinking.render(listOf(TokenField.Output)))
    }

    @Test
    fun `a blank model drops its line rather than an empty one`() {
        assertEquals(
            listOf("in: 63524/0", "out: 0/238", "12 t/s", "⏱ 19s  19:28"),
            openAi.copy(model = "").lines(TokenField.DEFAULT),
        )
    }

    @Test
    fun `the last line is time used and the wall clock together`() {
        // Both halves are wanted when looking for an answer you read this
        // morning, so they share the line rather than taking one each.
        assertEquals("⏱ 19s  19:28", openAi.render(listOf(TokenField.Time)))
        // Either half alone still keeps the line.
        assertEquals("⏱ 19s", openAi.copy(clock = "").render(listOf(TokenField.Time)))
        assertEquals("19:28", openAi.copy(millis = 0L).render(listOf(TokenField.Time)))
    }
}
