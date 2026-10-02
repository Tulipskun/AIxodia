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
        Note("เลือกโมเดลก่อน จึงจะเห็นว่าโมเดลนั้นปรับอะไรได้")
        return
    }
    if (!model.supportsAnyGenerationKnob()) {
        Note("โมเดลนี้ไม่มีการตั้งค่าการตอบให้ปรับ")
        return
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (model.supportsThinking) {
            ChoiceRow(
                label = "ระดับการคิด",
                help = "ให้โมเดลคิดก่อนตอบ ยิ่งสูงยิ่งใช้เวลาและ token มากขึ้น · ไม่บังคับคือให้ค่าเริ่มต้นของโมเดล",
                options = GenerationSettings.thinkingLevels,
                names = mapOf("" to "ไม่บังคับ", "low" to "ต่ำ", "medium" to "กลาง", "high" to "สูง"),
                selected = settings.thinkingLevel,
                onSelect = { onChange(settings.copy(thinkingLevel = it)) },
                onClear = { onClear("thinking_level") },
            )
        }
        if (model.supportsTemperature) {
            NumberRow(
                label = "temperature",
                help = "ต่ำคือคำตอบตายตัว สูงคือหลากหลาย · ไม่ได้ตั้งคือให้โมเดลใช้ค่าของตัวเอง",
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
                help = "ขอบเขตของคำที่พิจารณาต่อคำ ค่าต่ำคือเลือกแค่คำที่มั่นใจที่สุด",
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
                help = "จำนวนคำที่พิจารณาต่อคำ ตัวเลขที่เล็กกว่า 1 เป็นสัดส่วนแทนจำนวน",
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
                help = "หนึ่งบรรทัดต่อหนึ่งคำที่ทำให้หยุด เว้นวรรคคือไม่ใช้ค่านี้",
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
                help = "ลดการพูดคำที่เคยพูดไปแล้ว ค่าติดลบคือยิ่งย้ำคำเดิม",
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
                help = "ลดการใช้คำบ่อย ๆ ค่าติดลบคือยิ่งย้ำคำที่ใช้บ่อย",
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
                help = "ใส่ตัวเลขเดิมแล้วคำตอบจะทำซ้ำได้ เว้นวรรคคือสุ่มใหม่ทุกครั้ง",
                value = settings.seed?.toString().orEmpty(),
                numeric = true,
                onChange = { onChange(settings.copy(seed = it.trim().toLongOrNull())) },
            )
        }
        TextRow(
            label = "max output tokens",
            help = "เพดานความยาวคำตอบ 0 คือไม่จำกัด ให้โมเดลใช้ค่าของตัวเอง",
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
                    Text("ล้าง", style = MaterialTheme.typography.labelMedium)
                }
            } else {
                Text(
                    "ค่าเริ่มต้นของโมเดล",
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
                    Text("ล้าง", style = MaterialTheme.typography.labelMedium)
                }
            } else {
                Text(
                    "ค่าเริ่มต้นของโมเดล",
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
                    "$label, ยังไม่ได้ตั้ง ลากเพื่อตั้งค่า"
                } else {
                    "$label ${display(current)}, ลากเพื่อเปลี่ยน"
                }
            },
        )
        Text(
            if (value == null) "ยังไม่ได้ตั้ง" else display(current),
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
