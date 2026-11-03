package com.tulipskun.aixodia.ui.settings

import com.tulipskun.aixodia.data.model.GenerationSettings
import com.tulipskun.aixodia.data.model.ModelView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unset and zero are different answers, and the whole card depends on telling
 * them apart: unset leaves the provider on its own default, while zero is an
 * instruction the model was asked to obey.
 */
class GenerationSettingsTest {

    @Test
    fun `a block the reader never touched is not set`() {
        assertFalse(GenerationSettings().isSet)
    }

    @Test
    fun `a zero the reader chose counts as set`() {
        assertTrue(GenerationSettings(temperature = 0.0).isSet)
        assertTrue(GenerationSettings(topP = 0.0).isSet)
        assertTrue(GenerationSettings(seed = 0L).isSet)
    }

    @Test
    fun `an empty thinking level is not a choice`() {
        assertFalse(GenerationSettings(thinkingLevel = "").isSet)
        assertTrue(GenerationSettings(thinkingLevel = "high").isSet)
    }

    @Test
    fun `blank stop sequences count as no stop sequences`() {
        assertFalse(GenerationSettings(stopSequences = emptyList()).isSet)
        assertFalse(GenerationSettings(stopSequences = listOf("  ")).isSet)
        assertTrue(GenerationSettings(stopSequences = listOf("END")).isSet)
    }

    @Test
    fun `an output cap of zero means no cap`() {
        assertFalse(GenerationSettings(maxOutputTokens = 0).isSet)
        assertTrue(GenerationSettings(maxOutputTokens = 512).isSet)
    }

    @Test
    fun `the four providers' thinking levels are what the daemon accepts`() {
        assertTrue(GenerationSettings.thinkingLevels.contains(""))
        assertTrue(GenerationSettings.thinkingLevels.contains("low"))
        assertTrue(GenerationSettings.thinkingLevels.contains("medium"))
        assertTrue(GenerationSettings.thinkingLevels.contains("high"))
        assertFalse(GenerationSettings.thinkingLevels.contains("minimal"))
    }

    /**
     * A reasoning model pins the sampling parameters, so it reports no
     * temperature. The card must not offer one it would be refused for.
     */
    @Test
    fun `a reasoning model offers reasoning and no temperature`() {
        val model = ModelView(id = "gpt-5", supportsThinking = true, supportsTemperature = false)
        assertTrue(model.supportsAnyGenerationKnob())
    }

    @Test
    fun `a model with nothing adjustable says so`() {
        val model = ModelView(id = "some-model")
        assertFalse(model.supportsAnyGenerationKnob())
    }

    @Test
    fun `one supported knob is enough to show the card`() {
        val model = ModelView(id = "claude-3-7-sonnet", supportsThinking = true)
        assertTrue(model.supportsAnyGenerationKnob())
    }
}

/**
 * Clearing a knob has to read back as unset, and the name is what tells the
 * daemon to remove it. Leaving the value alone would be read as "not mentioned",
 * which now means keep what is stored.
 */
class ClearKnobTest {

    @Test
    fun `clearing a knob leaves nothing behind`() {
        val full = GenerationSettings(
            thinkingLevel = "high",
            temperature = 0.3,
            topP = 0.9,
            topK = 0.4,
            stopSequences = listOf("END"),
            presencePenalty = 0.5,
            frequencyPenalty = -1.0,
            seed = 7L,
            maxOutputTokens = 4096,
        )
        for (knob in knobNames) {
            val cleared = withoutKnob(full, knob)
            if (cleared == full) {
                fail("clearing $knob changed nothing")
            }
        }
    }

    @Test
    fun `clearing one knob leaves the others alone`() {
        val full = GenerationSettings(thinkingLevel = "high", temperature = 0.3, maxOutputTokens = 4096)
        val cleared = withoutKnob(full, "temperature")
        assertFalse(cleared.isSet.let { it && cleared.temperature == null && cleared.thinkingLevel.isBlank() })
        assertTrue(cleared.temperature == null)
        assertTrue(cleared.thinkingLevel == "high")
        assertTrue(cleared.maxOutputTokens == 4096)
    }

    @Test
    fun `an unknown knob name changes nothing rather than clearing everything`() {
        val full = GenerationSettings(temperature = 0.3)
        assertTrue(withoutKnob(full, "temprature") == full)
    }

    private val knobNames = listOf(
        "thinking_level", "temperature", "top_p", "top_k", "stop_sequences",
        "presence_penalty", "frequency_penalty", "seed", "max_output_tokens",
    )
}
