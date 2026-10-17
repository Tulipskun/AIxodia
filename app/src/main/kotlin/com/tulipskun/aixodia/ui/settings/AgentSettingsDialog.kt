package com.tulipskun.aixodia.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tulipskun.aixodia.data.model.AgentRoute
import com.tulipskun.aixodia.data.model.ProviderView

/**
 * Popup for agent model settings: two columns (main / sub), each with dropdowns
 * for provider, model, thinking level and temperature, plus an API-pool note.
 * Sub agent reuses the same session/agent system as main (falls back to main
 * when left blank), so no parallel machinery is needed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSettingsDialog(
    providers: List<ProviderView>,
    modelsByProvider: Map<String, List<com.tulipskun.aixodia.data.model.ModelView>>,
    mainRoute: AgentRoute,
    subRoute: AgentRoute,
    onMainRoute: (AgentRoute) -> Unit,
    onSubRoute: (AgentRoute) -> Unit,
    onDismiss: () -> Unit,
    onSave: (AgentRoute, AgentRoute) -> Unit,
) {
    var main by remember { mutableStateOf(mainRoute) }
    var sub by remember { mutableStateOf(subRoute) }
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
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(main, sub) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
