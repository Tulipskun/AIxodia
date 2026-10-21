package com.tulipskun.aixodia.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tulipskun.aixodia.data.model.AgentRoute
import com.tulipskun.aixodia.data.model.GenerationSettings
import com.tulipskun.aixodia.data.model.ModelView
import com.tulipskun.aixodia.data.model.ProviderStatus

/** Popup for the global main-agent model and its response style. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSettingsDialog(
    providers: List<ProviderStatus>,
    modelsByProvider: Map<String, List<ModelView>>,
    mainRoute: AgentRoute,
    mainGen: GenerationSettings,
    onDismiss: () -> Unit,
    onSave: (AgentRoute, GenerationSettings) -> Unit,
) {
    var main by remember { mutableStateOf(mainRoute) }
    var mainG by remember { mutableStateOf(mainGen) }
    var expMainThink by remember { mutableStateOf(false) }
    var expandedMainProv by remember { mutableStateOf(false) }
    var expandedMainModel by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Agent model") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExposedDropdownMenuBox(expanded = expandedMainProv, onExpandedChange = { expandedMainProv = it }) {
                    OutlinedTextField(
                        value = main.provider,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Provider") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedMainProv) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = expandedMainProv, onDismissRequest = { expandedMainProv = false }) {
                        providers.forEach { p ->
                            DropdownMenuItem(text = { Text(p.id) }, onClick = {
                                val models = modelsByProvider[p.id].orEmpty()
                                main = AgentRoute(p.id, models.firstOrNull()?.id.orEmpty())
                                expandedMainProv = false
                            })
                        }
                    }
                }
                ExposedDropdownMenuBox(expanded = expandedMainModel, onExpandedChange = { expandedMainModel = it }) {
                    OutlinedTextField(
                        value = main.model,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Model") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedMainModel) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = expandedMainModel, onDismissRequest = { expandedMainModel = false }) {
                        modelsByProvider[main.provider].orEmpty().forEach { m ->
                            DropdownMenuItem(text = { Text(m.id) }, onClick = {
                                main = main.copy(model = m.id)
                                expandedMainModel = false
                            })
                        }
                    }
                }
                ExposedDropdownMenuBox(expanded = expMainThink, onExpandedChange = { expMainThink = it }) {
                    OutlinedTextField(
                        value = mainG.thinkingLevel.ifBlank { "default" },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Thinking level") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expMainThink) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = expMainThink, onDismissRequest = { expMainThink = false }) {
                        listOf("", "low", "medium", "high").forEach { lvl ->
                            DropdownMenuItem(text = { Text(if (lvl.isBlank()) "Default" else lvl.replaceFirstChar { it.uppercase() }) }, onClick = {
                                mainG = mainG.copy(thinkingLevel = lvl)
                                expMainThink = false
                            })
                        }
                    }
                }
                OutlinedTextField(
                    value = mainG.temperature?.toString() ?: "",
                    onValueChange = { v -> mainG = mainG.copy(temperature = v.toDoubleOrNull()) },
                    label = { Text("Temperature") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(main, mainG) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
