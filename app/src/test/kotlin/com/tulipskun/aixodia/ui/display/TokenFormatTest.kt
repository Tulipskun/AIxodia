package com.tulipskun.aixodia.ui.display

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The footer text is data, not drawing, so it can be checked without a screen.
 * These lock the two things a reader would otherwise have to guess: that a
 * count is written part/total, and that a count nobody reported is absent
 * rather than printed as zero.
 */
class TokenFormatTest {

    private val openAiStyle = TokenCounts(
        inputFresh = 63875,
        inputTotal = 127683,
        outputAnswer = 68,
        outputTotal = 68,
        cacheRead = 63808,
        ratePerSecond = 5.7,
        millis = 12000,
    )

    @Test
    fun `the default row reads the way a developer expects`() {
        assertEquals("in: 63875/127683 · out: 68/68 · 6 t/s · ⏱ 12s", openAiStyle.render(TokenField.DEFAULT))
    }

    @Test
    fun `a count the provider did not report is left out, not zeroed`() {
        // Anthropic has no reasoning count, so a reasoning field must vanish
        // rather than print 0, which reads as a measurement.
        val anthropic = openAiStyle.copy(reasoning = 0, cacheRead = 0, inputTotal = 63875)
        assertEquals("in: 63875/63875 · out: 68/68 · 6 t/s · ⏱ 12s", anthropic.render(TokenField.DEFAULT))
    }

    @Test
    fun `the order is the configured order, not the declaration order`() {
        assertEquals("⏱ 12s · out: 68/68 · in: 63875/127683", openAiStyle.render(listOf(TokenField.Time, TokenField.Output, TokenField.Input)))
    }

    @Test
    fun `cache and reasoning appear only when the provider reported them`() {
        val row = openAiStyle.render(listOf(TokenField.Input, TokenField.CacheRead, TokenField.Reasoning, TokenField.CacheWrite))
        assertEquals("in: 63875/127683 · cache 63808", row)
    }

    @Test
    fun `a spec round-trips through the preference string`() {
        val spec = "Input,Output,CacheRead,Reasoning,Rate,Time"
        assertEquals(spec, TokenField.render(TokenField.parse(spec)))
        // And the short labels a reader would type resolve to the same fields.
        assertEquals(spec, TokenField.render(TokenField.parse("in,out,cache,think,rate,time")))
    }

    @Test
    fun `a hand-edited spec with a typo keeps the fields it did name`() {
        val parsed = TokenField.parse("in, output, nonsense, rate")
        assertEquals(listOf(TokenField.Input, TokenField.Output, TokenField.Rate), parsed)
        // The two spellings a reader might type both resolve: the enum name and
        // the label they can see in the footer.
        assertEquals(listOf(TokenField.CacheRead, TokenField.CacheWrite), TokenField.parse("cache,cacheWrite"))
        // Nothing usable means the default, so a typo cannot blank the footer.
        assertEquals(TokenField.DEFAULT, TokenField.parse("   "))
    }

    @Test
    fun `twelve seconds reads as twelve seconds, not twelve point zero`() {
        // formatSeconds drops the decimal above ten seconds, so a long turn does
        // not get a spurious ".0s" that means nothing to a reader.
        assertEquals("in: 1/1 · out: 1/1 · 1 t/s · ⏱ 12s", TokenCounts(1, 1, 1, 1, ratePerSecond = 1.0, millis = 12_000).render(TokenField.DEFAULT))
    }

    @Test
    fun `the answer count excludes reasoning, because those tokens are not words read`() {
        val withThinking = openAiStyle.copy(outputAnswer = 40, outputTotal = 68, reasoning = 28)
        assertEquals("out: 40/68 · think 28", withThinking.render(listOf(TokenField.Output, TokenField.Reasoning)))
    }
}
