package com.tulipskun.aixodia.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tulipskun.aixodia.data.model.GenerationSettings
import com.tulipskun.aixodia.data.model.ModelView
import kotlin.math.roundToInt

/**
 * The knobs for the model that is actually selected, and only those it takes.
 *
 * The daemon reports what each model accepts. A control for something the model
 * would refuse is not shown greyed out: it is left out, with one line saying the
 * model has no such setting. A disabled slider invites the reader to wonder what
 * is wrong with it rather than telling them the answer.
 *
 * Each text field holds its own text so a half-typed number is not normalised
 * away under the reader's hands; the card therefore re-reads [settings] when it is
 * given a different object, which is what a reload produces.
 */
@Composable
fun GenerationSettingsCard(
    model: ModelView?,
    settings: GenerationSettings,
    onChange: (GenerationSettings) -> Unit,
    onClear: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (model == null) {
        Note("Select a model first to see what it can be tuned for.")
        return
    }
    if (!model.supportsAnyGenerationKnob()) {
        Note("This model has no adjustable response settings.")
        return
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (model.supportsThinking) {
            ChoiceRow(
                label = "Reasoning effort",
                help = "Let the model think before answering. Higher means more time and tokens. Blank uses the model default.",
                options = GenerationSettings.thinkingLevels,
                names = mapOf("" to "Default", "low" to "Low", "medium" to "Medium", "high" to "High"),
                selected = settings.thinkingLevel,
                onSelect = { onChange(settings.copy(thinkingLevel = it)) },
                onClear = { onClear("thinking_level") },
            )
        }
        if (model.supportsTemperature) {
            NumberRow(
                label = "temperature",
                help = "Low means deterministic, high means varied. Blank uses the model default.",
                value = settings.temperature,
                range = 0f..2f,
                steps = 19,
                display = { "%.2f".format(it) },
                onChange = { onChange(settings.copy(temperature = it)) },
                onClear = { onClear("temperature") },
            )
        }
        if (model.supportsTopP) {
            NumberRow(
                label = "top_p",
                help = "Token sampling cutoff. Lower means only the most confident tokens.",
                value = settings.topP,
                range = 0f..1f,
                steps = 19,
                display = { "%.2f".format(it) },
                onChange = { onChange(settings.copy(topP = it)) },
                onClear = { onClear("top_p") },
            )
        }
        if (model.supportsTopK) {
            NumberRow(
                label = "top_k",
                help = "Number of tokens considered per step. Values below 1 are a fraction.",
                value = settings.topK,
                range = 0f..64f,
                steps = 63,
                display = { if (it < 1f) "%.2f".format(it) else it.roundToInt().toString() },
                onChange = { onChange(settings.copy(topK = it)) },
                onClear = { onClear("top_k") },
            )
        }
        if (model.supportsStopSequences) {
            TextRow(
                label = "stop sequences",
                help = "One stop sequence per line. Blank disables.",
                value = settings.stopSequences?.joinToString("\n").orEmpty(),
                singleLine = false,
                onChange = { text ->
                    val cleaned = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    onChange(settings.copy(stopSequences = cleaned.ifEmpty { null }))
                },
                onClear = { onClear("stop_sequences") },
            )
        }
        if (model.supportsPresencePenalty) {
            NumberRow(
                label = "presence penalty",
                help = "Reduces repetition of already-used tokens. Negative values reinforce repeats.",
                value = settings.presencePenalty,
                range = -2f..2f,
                steps = 31,
                display = { "%.2f".format(it) },
                onChange = { onChange(settings.copy(presencePenalty = it)) },
                onClear = { onClear("presence_penalty") },
            )
        }
        if (model.supportsFrequencyPenalty) {
            NumberRow(
                label = "frequency penalty",
                help = "Reduces frequent tokens. Negative values reinforce frequent tokens.",
                value = settings.frequencyPenalty,
                range = -2f..2f,
                steps = 31,
                display = { "%.2f".format(it) },
                onChange = { onChange(settings.copy(frequencyPenalty = it)) },
                onClear = { onClear("frequency_penalty") },
            )
        }
        if (model.supportsSeed) {
            TextRow(
                label = "seed",
                help = "Same seed reproduces the answer. Blank randomises each time.",
                value = settings.seed?.toString().orEmpty(),
                numeric = true,
                onChange = { onChange(settings.copy(seed = it.trim().toLongOrNull())) },
            )
        }
        TextRow(
            label = "max output tokens",
            help = "Answer length cap. 0 means unlimited; blank uses the model default.",
            value = if (settings.maxOutputTokens == 0) "" else settings.maxOutputTokens.toString(),
            numeric = true,
            onChange = { onChange(settings.copy(maxOutputTokens = it.trim().toIntOrNull()?.takeIf { n -> n > 0 } ?: 0)) },
            onClear = { onClear("max_output_tokens") },
        )
    }
}

internal fun ModelView.supportsAnyGenerationKnob(): Boolean =
    supportsThinking || supportsTemperature || supportsTopP || supportsTopK ||
        supportsStopSequences || supportsPresencePenalty || supportsFrequencyPenalty || supportsSeed

@Composable
private fun ChoiceRow(
    label: String,
    help: String,
    options: List<String>,
    names: Map<String, String>,
    selected: String,
    onSelect: (String) -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            if (selected.isNotBlank()) {
                TextButton(onClick = onClear) {
                    Text("Clear", style = MaterialTheme.typography.labelMedium)
                }
            } else {
                Text(
                    "Model default",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = selected == option,
                    onClick = { onSelect(option) },
                    label = { Text(names[option] ?: option) },
                )
            }
        }
        Note(help)
    }
}

/**
 * A knob that is either a number or unset. Unset is not zero: it says so, and can
 * be put back, so the reader can always tell a deliberate choice from a number the
 * app filled in on their behalf.
 */
@Composable
private fun NumberRow(
    label: String,
    help: String,
    value: Double?,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: (Float) -> String,
    onChange: (Double?) -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            if (value != null) {
                TextButton(onClick = onClear) {
                    Text("Clear", style = MaterialTheme.typography.labelMedium)
                }
            } else {
                Text(
                    "Model default",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val current = value?.toFloat() ?: range.start
        Slider(
            value = current,
            onValueChange = { onChange(it.toDouble()) },
            valueRange = range,
            steps = steps,
            modifier = Modifier.semantics {
                contentDescription = if (value == null) {
                    "$label, not set — drag to set"
                } else {
                    "$label ${display(current)} — drag to change"
                }
            },
        )
        Text(
            if (value == null) "Not set" else display(current),
            style = MaterialTheme.typography.bodyMedium,
        )
        HorizontalDivider()
        Note(help)
    }
}

@Composable
private fun TextRow(
    label: String,
    help: String,
    value: String,
    numeric: Boolean = false,
    singleLine: Boolean = true,
    onChange: (String) -> Unit,
    onClear: () -> Unit = {},
) {
    // The field keeps its own text so a half-typed value is not normalised away
    // while it is being typed; [value] seeds it when this row is first shown.
    var field by rememberSaveable(label) { mutableStateOf(value) }
    LabelledField(
        value = field,
        onValueChange = {
            field = it
            onChange(it)
        },
        label = label,
        hint = help,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        keyboardOptions = if (numeric) {
            KeyboardOptions(keyboardType = KeyboardType.Number)
        } else {
            KeyboardOptions.Default
        },
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
