package com.tulipskun.aixodia.ui.display

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tulipskun.aixodia.data.model.UiItem
import com.tulipskun.aixodia.data.model.UiNode
import com.tulipskun.aixodia.data.model.UiOption
import com.tulipskun.aixodia.data.model.UiScreen

/**
 * Renders a screen the daemon composed. The app owns the primitives below;
 * the spec owns their arrangement and wording, so a new screen ships from the
 * daemon with no new APK.
 *
 * A node's [action] and [params] are handed to [onAction] verbatim — the
 * renderer knows nothing about what an action means.
 */
@Composable
fun SpecScreen(
    screen: UiScreen,
    values: Map<String, String> = emptyMap(),
    onAction: (String, Map<String, String>) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (screen.title.isNotBlank()) {
            Text(fillVars(screen.title, values), style = MaterialTheme.typography.headlineSmall)
        }
        if (screen.subtitle.isNotBlank()) {
            Text(
                fillVars(screen.subtitle, values),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        screen.body.forEach { SpecNode(it, values, onAction) }
    }
}

@Composable
fun SpecNode(
    node: UiNode,
    values: Map<String, String>,
    onAction: (String, Map<String, String>) -> Unit,
) {
    when (node.type) {
        "text" -> Text(fillVars(node.value, values), style = specTextStyle(node.style))

        "section" -> Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (node.label.isNotBlank()) {
                    Text(fillVars(node.label, values), style = MaterialTheme.typography.titleMedium)
                }
                if (node.subtitle.isNotBlank()) {
                    Text(
                        fillVars(node.subtitle, values),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                node.children.forEach { SpecNode(it, values, onAction) }
            }
        }

        "button" -> {
            val label = fillVars(node.label, values)
            val click = { onAction(node.action, node.params) }
            when (node.style) {
                "outlined" -> OutlinedButton(onClick = click, modifier = Modifier.fillMaxWidth()) { Text(label) }
                "text" -> TextButton(onClick = click, modifier = Modifier.fillMaxWidth()) { Text(label) }
                else -> Button(onClick = click, modifier = Modifier.fillMaxWidth()) { Text(label) }
            }
        }

        "toggle" -> {
            val current = values[node.key]?.toBooleanStrictOrNull() ?: node.checked
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (node.label.isNotBlank()) {
                        Text(fillVars(node.label, values), style = MaterialTheme.typography.bodyLarge)
                    }
                    if (node.subtitle.isNotBlank()) {
                        Text(
                            fillVars(node.subtitle, values),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = current,
                    onCheckedChange = { on ->
                        onAction(node.action, node.params + mapOf("key" to node.key, "value" to on.toString()))
                    },
                )
            }
        }

        "field" -> OutlinedTextField(
            value = values[node.key] ?: fillVars(node.value, values),
            onValueChange = { onAction(node.action, node.params + mapOf("key" to node.key, "value" to it)) },
            label = { Text(fillVars(node.label, values)) },
            placeholder = { Text(fillVars(node.placeholder, values)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        "list" -> node.items.forEach { item ->
            SpecListRow(item, values, onAction)
        }

        "chips" -> Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            node.options.forEach { option ->
                SpecChip(option, node.selected, values, onAction)
            }
        }

        "row" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            node.children.forEach { SpecNode(it, values, onAction) }
        }

        "column" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            node.children.forEach { SpecNode(it, values, onAction) }
        }

        "divider" -> HorizontalDivider()

        else -> Unit // An unknown primitive is ignored, never a crash.
    }
}

@Composable
private fun SpecListRow(
    item: UiItem,
    values: Map<String, String>,
    onAction: (String, Map<String, String>) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = item.action.isNotBlank()) { onAction(item.action, item.params) }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(fillVars(item.title, values), style = MaterialTheme.typography.titleMedium)
            if (item.subtitle.isNotBlank()) {
                Text(
                    fillVars(item.subtitle, values),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (item.trailing.isNotBlank()) {
            Text(fillVars(item.trailing, values), style = MaterialTheme.typography.labelMedium)
        } else if (item.action.isNotBlank()) {
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SpecChip(
    option: UiOption,
    selectedValue: String,
    values: Map<String, String>,
    onAction: (String, Map<String, String>) -> Unit,
) {
    FilterChip(
        selected = selectedValue.isNotBlank() && selectedValue == option.value,
        onClick = { onAction(option.action, option.params + mapOf("value" to option.value)) },
        label = { Text(fillVars(option.label, values)) },
    )
}

@Composable
private fun specTextStyle(style: String) = when (style) {
    "title" -> MaterialTheme.typography.titleLarge
    "subtitle" -> MaterialTheme.typography.titleMedium
    "caption" -> MaterialTheme.typography.labelSmall
    else -> MaterialTheme.typography.bodyMedium
}

/**
 * Replaces {key} with the stored value of that key, so a spec screen can show
 * live state (a saved preference, the current model) without the app knowing
 * what the key means.
 */
fun fillVars(text: String, values: Map<String, String>): String {
    if (text.isEmpty() || values.isEmpty() || '{' !in text) return text
    var out = text
    for ((key, value) in values) {
        out = out.replace("{$key}", value)
    }
    return out
}
