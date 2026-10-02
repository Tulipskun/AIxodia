package com.tulipskun.aixodia.ui.settings

import com.tulipskun.aixodia.data.model.GenerationSettings
import com.tulipskun.aixodia.data.model.ModelView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
