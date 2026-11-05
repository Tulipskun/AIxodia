package com.tulipskun.aixodia.ui.files

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tulipskun.aixodia.SettingsStore
import com.tulipskun.aixodia.data.remote.FilesApi
import com.tulipskun.aixodia.data.remote.RemoteDirectory
import com.tulipskun.aixodia.data.remote.RemoteFile
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.ln
import kotlin.math.pow

private enum class FileAction { Rename, Permissions, Delete }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(settings: SettingsStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val api = remember(settings, context) { FilesApi(settings, context.contentResolver) }
    val scope = rememberCoroutineScope()
    var path by rememberSaveable { mutableStateOf("/") }
    var pathInput by rememberSaveable { mutableStateOf("/") }
    var listing by remember { mutableStateOf<RemoteDirectory?>(null) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<RemoteFile?>(null) }
    var action by remember { mutableStateOf<FileAction?>(null) }
    var editName by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("0644") }
    var pendingDownload by remember { mutableStateOf<RemoteFile?>(null) }

    fun load(target: String) {
        path = target
        pathInput = target
        loading = true
        error = ""
        scope.launch {
            runCatching { api.list(target) }
                .onSuccess { listing = it; path = it.path; pathInput = it.path }
                .onFailure { error = it.message ?: "Cannot list folder" }
            loading = false
        }
    }

    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = api.displayName(uri)
            busy = true
            progress = 0L to -1L
            scope.launch {
                runCatching {
                    api.upload(uri, path, name) { done, total -> progress = done to total }
                }.onFailure { error = it.message ?: "Upload failed" }
                busy = false
                progress = null
                load(path)
            }
        }
    }
    val downloadPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = pendingDownload
        if (uri != null && file != null) {
            busy = true
            progress = 0L to -1L
            scope.launch {
                runCatching {
                    api.download(file.path, uri) { done, total -> progress = done to total }
                }.onFailure { error = it.message ?: "Download failed" }
                busy = false
                progress = null
                pendingDownload = null
            }
        } else pendingDownload = null
    }

    BackHandler {
        val parent = listing?.parent
        if (parent != null) load(parent) else onBack()
    }
    LaunchedEffect(Unit) { load("/") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Files") },
                navigationIcon = {
                    IconButton(onClick = {
                        val parent = listing?.parent
                        if (parent != null) load(parent) else onBack()
                    }) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { load(path) }, enabled = !busy) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { folderName = ""; newFolder = true }, enabled = !busy) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "New folder")
                    }
                    IconButton(onClick = { uploadPicker.launch(arrayOf("*/*")) }, enabled = !busy) {
                        Icon(Icons.Default.UploadFile, contentDescription = "Upload")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = pathInput,
                onValueChange = { pathInput = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Linux path") },
                trailingIcon = {
                    TextButton(onClick = { load(pathInput) }, enabled = !busy) { Text("Go") }
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { listing?.parent?.let(::load) }, enabled = listing?.parent != null && !busy) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "Parent folder")
                }
                Text(
                    listing?.path ?: path,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text((listing?.entries?.size ?: 0).toString() + " items", style = MaterialTheme.typography.labelSmall)
            }
            if (busy) {
                val value = progress
                if (value != null && value.second > 0L) {
                    LinearProgressIndicator(
                        progress = { (value.first.toFloat() / value.second).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(humanSize(value.first) + " / " + humanSize(value.second), style = MaterialTheme.typography.labelSmall)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Transferring…", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (error.isNotBlank()) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(listing?.entries.orEmpty(), key = { it.path }) { file ->
                        FileRow(
                            file = file,
                            enabled = !busy,
                            onOpen = {
                                if (file.isDirectory) load(file.path)
                                else {
                                    pendingDownload = file
                                    downloadPicker.launch(file.name)
                                }
                            },
                            onAction = {
                                selected = file
                                editName = file.name
                                mode = file.permissions.ifBlank { "0644" }
                                action = it
                            },
                        )
                    }
                }
            }
        }
    }

    if (newFolder) {
        AlertDialog(
            onDismissRequest = { newFolder = false },
            title = { Text("Create folder") },
            text = {
                OutlinedTextField(value = folderName, onValueChange = { folderName = it }, singleLine = true, label = { Text("Folder name") })
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = path.trimEnd('/') + "/" + folderName.trim()
                    busy = true
                    scope.launch {
                        runCatching { api.mkdir(target) }.onFailure { error = it.message ?: "Create failed" }
                        busy = false
                        newFolder = false
                        load(path)
                    }
                }, enabled = folderName.isNotBlank() && !busy) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { newFolder = false }) { Text("Cancel") } },
        )
    }

    val file = selected
    when (action) {
        FileAction.Rename -> if (file != null) AlertDialog(
            onDismissRequest = { action = null },
            title = { Text("Rename") },
            text = { OutlinedTextField(value = editName, onValueChange = { editName = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val target = file.path.substringBeforeLast('/') + "/" + editName.trim()
                    busy = true
                    scope.launch {
                        runCatching { api.rename(file.path, target) }.onFailure { error = it.message ?: "Rename failed" }
                        busy = false
                        action = null
                        selected = null
                        load(path)
                    }
                }, enabled = editName.isNotBlank() && editName != file.name && !busy) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { action = null }) { Text("Cancel") } },
        )
        FileAction.Permissions -> if (file != null) AlertDialog(
            onDismissRequest = { action = null },
            title = { Text("Permissions") },
            text = { OutlinedTextField(value = mode, onValueChange = { mode = it }, singleLine = true, label = { Text("Octal mode, e.g. 0755") }) },
            confirmButton = {
                TextButton(onClick = {
                    busy = true
                    scope.launch {
                        runCatching { api.chmod(file.path, mode) }.onFailure { error = it.message ?: "chmod failed" }
                        busy = false
                        action = null
                        selected = null
                        load(path)
                    }
                }, enabled = mode.matches(Regex("0?[0-7]{3,4}")) && !busy) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { action = null }) { Text("Cancel") } },
        )
        FileAction.Delete -> if (file != null) AlertDialog(
            onDismissRequest = { action = null },
            title = { Text("Delete " + file.name + "?") },
            text = { Text(if (file.isDirectory) "The folder and all its contents will be deleted." else "This file will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    busy = true
                    scope.launch {
                        runCatching { api.delete(file.path, recursive = file.isDirectory) }
                            .onFailure { error = it.message ?: "Delete failed" }
                        busy = false
                        action = null
                        selected = null
                        load(path)
                    }
                }, enabled = !busy) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { action = null }) { Text("Cancel") } },
        )
        null -> Unit
    }
}

@Composable
private fun FileRow(
    file: RemoteFile,
    enabled: Boolean,
    onOpen: () -> Unit,
    onAction: (FileAction) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onOpen).padding(vertical = 9.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            if (file.isDirectory) Icons.Default.Folder else Icons.Default.Description,
            contentDescription = null,
            tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            val detail = buildList {
                if (!file.isDirectory) add(humanSize(file.size))
                if (file.permissions.isNotBlank()) add(file.permissions)
                if (file.uid >= 0 && file.gid >= 0) add(file.uid.toString() + ":" + file.gid)
                if (file.modified > 0) add(DateFormat.getDateTimeInstance().format(Date(file.modified * 1000)))
            }.joinToString(" · ")
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            IconButton(onClick = { menu = true }, enabled = enabled) {
                Icon(Icons.Default.MoreVert, contentDescription = "File actions")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onAction(FileAction.Rename) })
                DropdownMenuItem(text = { Text("Permissions") }, onClick = { menu = false; onAction(FileAction.Permissions) })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onAction(FileAction.Delete) })
            }
        }
    }
}

private fun humanSize(bytes: Long): String {
    if (bytes < 1024) return bytes.toString() + " B"
    val units = listOf("KB", "MB", "GB", "TB")
    val exponent = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(1, units.size)
    return String.format("%.1f %s", bytes / 1024.0.pow(exponent), units[exponent - 1])
}
