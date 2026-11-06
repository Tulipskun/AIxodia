package com.tulipskun.aixodia.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.ui.state.ToggleableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tulipskun.aixodia.data.remote.BackupBrowse
import com.tulipskun.aixodia.data.remote.HistoryApi

/** Selects files and folders as independent backup paths from the filesystem root. */
@Composable
internal fun UnifiedBackupFolderManager(
    history: HistoryApi,
    includes: List<String>,
    onIncludesChange: (List<String>) -> Unit,
    onDone: () -> Unit,
) {
    var path by remember { mutableStateOf("/") }
    var listing by remember { mutableStateOf<BackupBrowse?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(path, reload) {
        loading = true
        val result = history.backupBrowse(path)
        listing = result.value
        error = result.error
        loading = false
    }

    val parent = listing?.parent.orEmpty()
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Select backup files and folders", style = MaterialTheme.typography.titleMedium)
        Text("Select any number of files or folders from anywhere on the engine.", style = MaterialTheme.typography.bodySmall)
        Text(path, style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { if (parent.isNotBlank()) path = parent }, enabled = parent.isNotBlank() && !loading) {
                Icon(Icons.Default.ArrowBack, contentDescription = null)
                Text("Up")
            }
            TextButton(onClick = { reload++ }, enabled = !loading) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Text("Reload")
            }
            TextButton(onClick = { onIncludesChange(emptyList()) }, enabled = includes.isNotEmpty()) {
                Text("Clear selection")
            }
        }
        Text("Selection rules: ${includes.size}", style = MaterialTheme.typography.labelMedium)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(listing?.entries.orEmpty(), key = { it.path }) { entry ->
                val relative = entry.path.removePrefix("/").trim('/')
                val selectionState = if (relative.isBlank()) ToggleableState.Off
                    else backupPathState(relative, includes)
                Row(
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable(enabled = entry.isDir) { path = entry.path }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (entry.isDir) Icons.Default.Folder else Icons.Default.Description,
                        contentDescription = if (entry.isDir) "Folder" else "File",
                        tint = if (entry.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(entry.name, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1)
                    TriStateCheckbox(
                        state = selectionState,
                        onClick = {
                            val next = updateBackupPathSelection(
                                relative,
                                selectionState != ToggleableState.On,
                                includes,
                            )
                            onIncludesChange(next)
                        },
                    )
                }
            }
        }
        HorizontalDivider()
        Text(
            "Selected paths are backed up independently. For example, select /root and /usr in the same list.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = {
                onDone()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Done") }
    }
}


private fun backupPathState(path: String, rules: List<String>): ToggleableState {
    fun selected(candidate: String): Boolean {
        val match = rules.mapNotNull { raw ->
            val excluded = raw.startsWith("!")
            val rule = raw.removePrefix("!")
            if (candidate == rule || candidate.startsWith("$rule/")) {
                rule.length to !excluded
            } else null
        }.maxByOrNull { it.first }
        return match?.second ?: false
    }
    val own = selected(path)
    val descendants = rules.any { raw ->
        val rule = raw.removePrefix("!")
        rule.startsWith("$path/")
    }
    return when {
        own && !descendants -> ToggleableState.On
        !own && !descendants -> ToggleableState.Off
        else -> ToggleableState.Indeterminate
    }
}

private fun updateBackupPathSelection(
    path: String,
    select: Boolean,
    rules: List<String>,
): List<String> {
    val prefix = "$path/"
    val withoutSubtree = rules.filterNot { raw ->
        val rule = raw.removePrefix("!")
        rule == path || rule.startsWith(prefix)
    }.toMutableList()
    if (select) {
        withoutSubtree += path
    } else {
        val inherited = rules.any { raw ->
            !raw.startsWith("!") &&
                (path.startsWith(raw.removePrefix("!") + "/"))
        }
        if (inherited) withoutSubtree += "!$path"
    }
    return withoutSubtree.distinct().sorted()
}
