package com.tulipskun.aixodia.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tulipskun.aixodia.data.model.AgentRoute

/**
 * Shared building blocks for the settings screen — keeps connection /
 * provider / agent sections visually consistent like a typical app settings page.
 */
@Composable
fun StatusBanner(text: String) {
    val scheme = MaterialTheme.colorScheme
    val error = text.contains("ไม่", ignoreCase = true) ||
        text.contains("HTTP", ignoreCase = true) ||
        text.contains("ผิด", ignoreCase = true) ||
        text.contains("ล้ม", ignoreCase = true)
    Surface(
        color = if (error) scheme.errorContainer else scheme.secondaryContainer,
        contentColor = if (error) scheme.onErrorContainer else scheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (error) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text("  $text", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun SectionCard(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
fun RouteCard(
    title: String,
    route: AgentRoute,
    modelCount: Int,
    onPickProvider: () -> Unit,
    onPickModel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        PickerRow("provider", route.provider.ifBlank { "เลือก provider" }, onPick = onPickProvider)
        PickerRow(
            label = "model",
            value = route.model.ifBlank { "เลือก model" },
            hint = if (route.provider.isBlank()) "เลือก provider ก่อน" else "$modelCount โมเดลใน provider นี้",
            onPick = onPickModel,
        )
    }
}

@Composable
fun PickerRow(label: String, value: String, hint: String = "", onPick: () -> Unit) {
    Surface(
        onClick = onPick,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                if (hint.isNotBlank()) {
                    Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.Default.ExpandMore, contentDescription = null)
        }
    }
}

/**
 * One text field, one anatomy: short label, the box, then the explanation.
 *
 * The screen used to write `OutlinedTextField` nine times with the label, the
 * placeholder and the explanation arranged differently in each place. One of them
 * put a whole sentence in the label — "ที่อยู่ daemon (tunnel) — แชทสด และ
 * provider/model" — which wrapped to two lines and drew the second line across
 * the field's own outline.
 *
 * A Material label floats above the box, so it has to stay a label. Anything that
 * needs explaining belongs in [hint], which sits under the box where there is
 * room for it, and is omitted when there is nothing to say.
 */
@Composable
fun LabelledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailing: @Composable (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = hint?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = minLines,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        trailingIcon = trailing,
        leadingIcon = leading,
        modifier = modifier.fillMaxWidth(),
    )
}
