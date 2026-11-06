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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tulipskun.aixodia.data.remote.BackupBrowse
import com.tulipskun.aixodia.data.remote.HistoryApi

/**
 * Selects the files and folders a backup covers, browsing the engine's tree.
 *
 * Rules are relative to `/` — the same form the engine stores and the picker
 * reads back — so a folder's checkbox reflects rules made at any depth, not just
 * ones made while standing in its parent.
 */
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
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Select backup files and folders", style = MaterialTheme.typography.titleMedium)
        Text(
            "Select a folder to include everything inside it. Uncheck a child to exclude that subtree.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.padding(end = 4.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                path,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(
                onClick = { if (parent.isNotBlank()) path = parent },
                enabled = parent.isNotBlank() && !loading,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Text("Up", Modifier.padding(start = 4.dp))
            }
            TextButton(onClick = { reload++ }, enabled = !loading) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Text("Reload", Modifier.padding(start = 4.dp))
            }
            TextButton(
                onClick = { onIncludesChange(emptyList()) },
                enabled = includes.isNotEmpty(),
            ) { Text("Clear") }
        }
        if (loading) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.padding(start = 2.dp), strokeWidth = 2.dp)
                Text(
                    "reading ${path}…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (error.isNotBlank()) {
            StatusBanner(error, error = true)
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(listing?.entries.orEmpty(), key = { it.path }) { entry ->
                BackupEntryRow(
                    name = entry.name,
                    isDir = entry.isDir,
                    state = backupPathState(entry.path.removePrefix("/").trim('/'), includes),
                    enabled = !loading,
                    onOpen = { path = entry.path },
                    onToggle = {
                        onIncludesChange(
                            updateBackupPathSelection(
                                entry.path.removePrefix("/").trim('/'),
                                backupPathState(entry.path.removePrefix("/").trim('/'), includes) !=
                                    ToggleableState.On,
                                includes,
                            ),
                        )
                    },
                )
            }
        }
        HorizontalDivider()
        Text(
            "${includes.size} selected · a checked folder includes its contents; " +
                "a partial checkbox marks excluded items inside it.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Done") }
    }
}

@Composable
private fun BackupEntryRow(
    name: String,
    isDir: Boolean,
    state: ToggleableState,
    enabled: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .clickable(enabled = enabled && isDir, onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isDir) Icons.Default.Folder else Icons.Default.Description,
                contentDescription = if (isDir) "Folder" else "File",
                tint = if (isDir) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                name,
                Modifier.weight(1f).padding(horizontal = 12.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The row opens the folder; the checkbox alone changes the selection.
            TriStateCheckbox(state = state, onClick = onToggle)
            if (isDir) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Open",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Whether a path is covered by the rules: the longest matching rule decides, the
 * same way the engine's scanner does, so a pick made anywhere in the tree shows
 * here. An empty relative path is the root itself, which no rule can select.
 */
internal fun backupPathState(path: String, rules: List<String>): ToggleableState {
    if (path.isBlank()) return ToggleableState.Off
    fun selected(candidate: String): Boolean {
        val match = rules.mapNotNull { raw ->
            val excluded = raw.startsWith("!")
            val rule = raw.removePrefix("!").trim('/')
            if (candidate == rule || candidate.startsWith("$rule/")) {
                rule.length to !excluded
            } else null
        }.maxByOrNull { it.first }
        return match?.second ?: false
    }
    val own = selected(path)
    val descendants = rules.any { raw ->
        val rule = raw.removePrefix("!").trim('/')
        rule.startsWith("$path/")
    }
    return when {
        own && !descendants -> ToggleableState.On
        !own && !descendants -> ToggleableState.Off
        else -> ToggleableState.Indeterminate
    }
}

internal fun updateBackupPathSelection(
    path: String,
    select: Boolean,
    rules: List<String>,
): List<String> {
    val prefix = "$path/"
    val withoutSubtree = rules.filterNot { raw ->
        val rule = raw.removePrefix("!").trim('/')
        rule == path || rule.startsWith(prefix)
    }.toMutableList()
    if (select) {
        withoutSubtree += path
    } else {
        val inherited = rules.any { raw ->
            !raw.startsWith("!") &&
                (path.startsWith(raw.removePrefix("!").trim('/') + "/"))
        }
        if (inherited) withoutSubtree += "!$path"
    }
    return withoutSubtree.distinct().sorted()
}
