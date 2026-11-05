package com.tulipskun.aixodia.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

/**
 * One directory browser owns root selection and all folder rules.
 * A directory is navigated into to configure its children; no second picker,
 * scope sheet, or include/exclude browser is opened.
 */
@Composable
internal fun UnifiedBackupFolderManager(
    history: HistoryApi,
    root: String,
    includes: List<String>,
    excludes: List<String>,
    onRootChange: (String) -> Unit,
    onIncludesChange: (List<String>) -> Unit,
    onExcludesChange: (List<String>) -> Unit,
    onDone: () -> Unit,
) {
    var path by remember(root) { mutableStateOf(root.ifBlank { "/" }) }
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

    val withinRoot = root.isNotBlank() && isUnder(path, root)
    val parent = listing?.parent.orEmpty()

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Backup location", style = MaterialTheme.typography.titleMedium)
        Text(root.ifBlank { "No backup root selected" }, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (path != root) {
                        onRootChange(path)
                        onIncludesChange(emptyList())
                    }
                },
                enabled = path != root && !loading,
            ) { Text("Use current folder as backup root") }
        }
        HorizontalDivider()
        Text(
            if (withinRoot) "Folders under backup root · $path"
            else "Browsing outside the backup root · choose this folder as the new root",
            style = MaterialTheme.typography.titleSmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { if (parent.isNotBlank()) path = parent },
                enabled = parent.isNotBlank() && !loading,
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = null)
                Text("Up")
            }
            TextButton(onClick = { reload++ }, enabled = !loading) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Text("Reload")
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(listing?.entries.orEmpty(), key = { it.path }) { entry ->
                val canEnter = entry.isDir && entry.canEnter
                val relative = if (withinRoot) relativise(root, entry.path) else ""
                val excludedPath = if (relative.isBlank()) "" else "$relative/"
                val excluded = excludedPath.isNotBlank() &&
                    excludes.any { it == excludedPath || it == relative }
                val included = relative.isNotBlank() &&
                    (includes.isEmpty() || includes.any {
                        it == relative || it.startsWith("$relative/") || relative.startsWith("$it/")
                    }) && !excluded
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null)
                    Text(
                        entry.name,
                        Modifier.weight(1f).padding(horizontal = 8.dp)
                            .clickable(enabled = canEnter) { path = entry.path },
                        maxLines = 1,
                    )
                    if (withinRoot && canEnter && relative.isNotBlank()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Checkbox(
                                checked = included,
                                onCheckedChange = {
                                    if (includes.isEmpty()) onExcludesChange(toggleExcludedFolder(excludes, relative))
                                    else onIncludesChange(toggleFolder(includes, relative))
                                },
                            )
                            Text("Backup", style = MaterialTheme.typography.labelSmall)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Checkbox(
                                checked = excluded,
                                onCheckedChange = {
                                    onExcludesChange(toggleExcludedFolder(excludes, relative))
                                },
                            )
                            Text("Exclude", style = MaterialTheme.typography.labelSmall)
                        }
                    } else if (canEnter) {
                        TextButton(onClick = { path = entry.path }) { Text("Open") }
                    }
                }
            }
        }
        HorizontalDivider()
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}
