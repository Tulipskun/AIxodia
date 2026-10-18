package com.tulipskun.aixodia.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tulipskun.aixodia.data.model.AgentRoute
import com.tulipskun.aixodia.data.model.ProviderStatus

/**
 * Popup for agent model settings: two columns (main / sub), each with dropdowns
 * for provider, model, thinking level and temperature.
 * Sub agent reuses the same session/agent system as main (falls back to main
 * when left blank), so no parallel machinery is needed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSettingsDialog(
    providers: List<ProviderStatus>,
    modelsByProvider: Map<String, List<com.tulipskun.aixodia.data.model.ModelView>>,
    mainRoute: AgentRoute,
    subRoute: AgentRoute,
    mainGen: com.tulipskun.aixodia.data.model.GenerationSettings,
    subGen: com.tulipskun.aixodia.data.model.GenerationSettings,
    onMainRoute: (AgentRoute) -> Unit,
    onSubRoute: (AgentRoute) -> Unit,
    onMainGen: (com.tulipskun.aixodia.data.model.GenerationSettings) -> Unit,
    onSubGen: (com.tulipskun.aixodia.data.model.GenerationSettings) -> Unit,
    onDismiss: () -> Unit,
    onSave: (AgentRoute, AgentRoute, com.tulipskun.aixodia.data.model.GenerationSettings, com.tulipskun.aixodia.data.model.GenerationSettings) -> Unit,
) {
    var main by remember { mutableStateOf(mainRoute) }
    var sub by remember { mutableStateOf(subRoute) }
    var mainG by remember { mutableStateOf(mainGen) }
    var subG by remember { mutableStateOf(subGen) }
    var expMainThink by remember { mutableStateOf(false) }
    var expSubThink by remember { mutableStateOf(false) }
    var expandedMainProv by remember { mutableStateOf(false) }
    var expandedMainModel by remember { mutableStateOf(false) }
    var expandedSubProv by remember { mutableStateOf(false) }
    var expandedSubModel by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Agent models") },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // ---- main column ----
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Main agent", style = MaterialTheme.typography.titleSmall)
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
                }
                // thinking level
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
                // ---- sub column ----
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sub agent", style = MaterialTheme.typography.titleSmall)
                    ExposedDropdownMenuBox(expanded = expandedSubProv, onExpandedChange = { expandedSubProv = it }) {
                        OutlinedTextField(
                            value = sub.provider,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Provider (blank = main)") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedSubProv) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                        )
                        ExposedDropdownMenu(expanded = expandedSubProv, onDismissRequest = { expandedSubProv = false }) {
                            DropdownMenuItem(text = { Text("(use main)") }, onClick = {
                                sub = AgentRoute("", "")
                                expandedSubProv = false
                            })
                            providers.forEach { p ->
                                DropdownMenuItem(text = { Text(p.id) }, onClick = {
                                    val models = modelsByProvider[p.id].orEmpty()
                                    sub = AgentRoute(p.id, models.firstOrNull()?.id.orEmpty())
                                    expandedSubProv = false
                                })
                            }
                        }
                    }
                    ExposedDropdownMenuBox(expanded = expandedSubModel, onExpandedChange = { expandedSubModel = it }) {
                        OutlinedTextField(
                            value = sub.model,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Model") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedSubModel) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                        )
                        ExposedDropdownMenu(expanded = expandedSubModel, onDismissRequest = { expandedSubModel = false }) {
                            modelsByProvider[sub.provider].orEmpty().forEach { m ->
                                DropdownMenuItem(text = { Text(m.id) }, onClick = {
                                    sub = sub.copy(model = m.id)
                                    expandedSubModel = false
                                })
                            }
                        }
                    }
                }
                ExposedDropdownMenuBox(expanded = expSubThink, onExpandedChange = { expSubThink = it }) {
                    OutlinedTextField(
                        value = subG.thinkingLevel.ifBlank { "default" },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Thinking level") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expSubThink) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = expSubThink, onDismissRequest = { expSubThink = false }) {
                        listOf("", "low", "medium", "high").forEach { lvl ->
                            DropdownMenuItem(text = { Text(if (lvl.isBlank()) "Default" else lvl.replaceFirstChar { it.uppercase() }) }, onClick = {
                                subG = subG.copy(thinkingLevel = lvl)
                                expSubThink = false
                            })
                        }
                    }
                }
                OutlinedTextField(
                    value = subG.temperature?.toString() ?: "",
                    onValueChange = { v -> subG = subG.copy(temperature = v.toDoubleOrNull()) },
                    label = { Text("Temperature") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(main, sub, mainG, subG) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
