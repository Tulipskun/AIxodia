package com.tulipskun.aixodia.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tulipskun.aixodia.data.runCatchingCancellable
import com.tulipskun.aixodia.data.model.AttachmentRef
import com.tulipskun.aixodia.data.remote.BackupRun
import com.tulipskun.aixodia.data.remote.BackupPreview
import com.tulipskun.aixodia.data.remote.BackupStatus
import com.tulipskun.aixodia.data.remote.HistoryApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The engine's own backup, on a page of its own.
 *
 * Every action here is a request to the daemon: it holds the hub token in D1, it
 * walks the tree and it is the only thing that talks to the bucket. What this
 * page adds over the line of text it replaces is a run that can be watched from
 * anywhere and an outcome that is still here after the watch stops — the job
 * belongs to the engine, so a run started from another device, or before this
 * app was closed, shows up on opening the page rather than being lost.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(history: HistoryApi, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<BackupStatus?>(null) }
    var readError by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var msgIsError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var askRestore by remember { mutableStateOf(false) }
    // Advanced once a second only while a job runs, so the elapsed clock moves
    // without the page polling the engine any harder.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var selectedTab by rememberSaveable { mutableStateOf(0) }

    // Config fields, filled from the engine once per visit: refilling on every
    // poll would overwrite whatever the reader is halfway through typing.
    var token by rememberSaveable { mutableStateOf("") }
    var bucket by rememberSaveable { mutableStateOf("") }
    var prefix by rememberSaveable { mutableStateOf("") }
    // A newline-joined list rather than a List<String>, so selected paths
    // survive the process being recreated. An empty list means no selection.
    var includesText by rememberSaveable { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var filled by remember { mutableStateOf(false) }

    // Sheets, one at a time: the scope sheet's "Add a folder" hands over to the
    // browser and takes the answer back, rather than stacking a sheet on a sheet.
    var showScope by remember { mutableStateOf(false) }
    val preview = remember { mutableStateOf<BackupPreview?>(null) }
    val previewError = remember { mutableStateOf("") }

    val includes = includesText.split('\n').map { it.trim() }.filter { it.isNotBlank() }

    val load: suspend () -> Unit = {
        val read = runCatchingCancellable { history.backupStatus() }
        val s = read.getOrNull()
        if (s == null) {
            readError = "The engine did not answer: " +
                (read.exceptionOrNull()?.message ?: "no reply")
        } else {
            readError = ""
            status = s
            if (!filled) {
                filled = true
                bucket = s.bucket
                prefix = s.prefix
                val previousRoot = s.root.trimEnd('/').removePrefix("/")
                includesText = if (previousRoot.isBlank()) {
                    s.includes.orEmpty().joinToString("\n")
                } else {
                    (s.includes.orEmpty().ifEmpty { listOf("") }).map { entry ->
                        if (entry.isBlank()) previousRoot
                        else if (entry == previousRoot || entry.startsWith("$previousRoot/")) entry
                        else "$previousRoot/${entry.trimStart('/')}"
                    }.joinToString("\n")
                }
            }
        }
    }

    // Two speeds: a run in flight is worth a read every two seconds, and an idle
    // engine only needs asking often enough that a job started elsewhere does not
    // take a minute to appear.
    LaunchedEffect(Unit) {
        while (true) {
            load()
            delay(if (status?.running == true) 2000L else 15000L)
        }
    }
    LaunchedEffect(status?.running) {
        while (status?.running == true) {
            now = System.currentTimeMillis()
            delay(1000L)
        }
    }

    val s = status
    val tabLabels = listOf("Activity", "Files & folders", "Storage", "Auto")
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (showScope) "Manage backup files and folders" else "Backup & restore") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (showScope) showScope = false else onBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad),
        ) {
            if (showScope && s != null) {
                UnifiedBackupFolderManager(
                    history = history,
                    includes = includes,
                    onIncludesChange = { chosen ->
                        includesText = chosen.joinToString("\n")
                        preview.value = null
                    },
                    onDone = { showScope = false },
                )
            } else {
            // Navigation stays visible while the selected workspace scrolls.
            // Each tab has one job, instead of making the operator pass through
            // every configuration card to reach the action they need.
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 16.dp,
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                tabLabels.forEachIndexed { index, label ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(label) },
                    )
                }
            }

            LastBackupStrip(s?.lastBackup)
            HorizontalDivider()

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .widthIn(max = 760.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (readError.isNotBlank()) StatusBanner(readError, error = true)
                if (msg.isNotBlank()) StatusBanner(msg, msgIsError)

                when (selectedTab) {
                    0 -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Backup activity", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Start a run, follow its progress, and inspect the last result.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        RunCard(
                            status = s,
                            now = now,
                            busy = busy,
                            onStart = {
                                busy = true; msg = ""
                                scope.launch {
                                    val out = history.backupStart()
                                    msg = out
                                    msgIsError = !out.startsWith("Backup started")
                                    load()
                                    busy = false
                                }
                            },
                            onRefresh = {
                                busy = true
                                scope.launch { load(); busy = false }
                            },
                        )
                        s?.run?.let { RunResultCard(it) }
                    }

                    1 -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Choose folders", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Select the files and folders to back up. Nothing is included until it is selected.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        ScopeCard(
                            status = s,
                            includes = includes,
                            busy = busy,
                            onEditScope = { showScope = true },
                            onSave = {
                                busy = true; msg = ""
                                scope.launch {
                                    val out = history.saveBackupConfig(
                                        token = token,
                                        bucket = bucket,
                                        prefix = prefix,
                                        excludes = emptyList(),
                                        includes = s?.includes?.let { includes },
                                        root = "/".takeIf { it != s?.root },
                                    )
                                    msg = out
                                    msgIsError = !out.startsWith("Backup settings saved")
                                    filled = false
                                    load()
                                    busy = false
                                }
                            },
                        )
                        PreviewCard(
                            preview = preview.value,
                            error = previewError.value,
                            busy = busy,
                            enabled = s != null && s.reportsScope && !s.running,
                            lastRun = s?.run,
                            onPreview = {
                                busy = true; msg = ""
                                previewError.value = ""
                                scope.launch {
                                    val read = history.backupPreview(
                                        root = "/".takeIf { it != s?.root }, 
                                        includes = s?.includes?.let { includes },
                                        excludes = emptyList(),
                                    )
                                    preview.value = read.value
                                    previewError.value = read.value?.let { "" } ?: read.error
                                    load()
                                    busy = false
                                }
                            },
                        )
                    }

                    2 -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Storage destination", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Configure the bucket and credentials used by the engine, then restore when needed.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        BucketCard(
                            status = s,
                            token = token, onToken = { token = it },
                            bucket = bucket, onBucket = { bucket = it },
                            prefix = prefix, onPrefix = { prefix = it },
                            showToken = showToken, onToggleToken = { showToken = !showToken },
                            busy = busy,
                            onSave = {
                                busy = true; msg = ""
                                scope.launch {
                                    val out = history.saveBackupConfig(token, bucket, prefix)
                                    msg = out
                                    msgIsError = !out.startsWith("Backup settings saved")
                                    filled = false
                                    load()
                                    busy = false
                                }
                            },
                        )
                        if (s != null) {
                            RestoreCard(
                                status = s,
                                busy = busy,
                                onRestore = { askRestore = true },
                            )
                        }
                    }

                    else -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Automatic backup", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Let the engine back up when the machine is idle, using the thresholds below.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AutoBackupCard(
                            auto = s?.auto,
                            busy = busy,
                            onSave = { enabled, idle, cpu, cooldown ->
                                busy = true; msg = ""
                                scope.launch {
                                    val out = history.saveBackupAuto(enabled, idle, cpu, cooldown)
                                    msg = out
                                    msgIsError = !out.startsWith("Auto-backup saved")
                                    filled = false
                                    load()
                                    busy = false
                                }
                            },
                        )
                    }
                }
            }
            }
        }
    }

    if (askRestore && s != null) {
        AlertDialog(
            onDismissRequest = { askRestore = false },
            title = { Text("Restore the backup?") },
            text = {
                Text(
                    "Restoring overwrites files under ${s.root} on the engine with " +
                        "the tree the bucket holds. Files it does not name are left " +
                        "where they are. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        askRestore = false
                        busy = true; msg = ""
                        scope.launch {
                            val out = history.backupRestore()
                            msg = out
                            msgIsError = !out.startsWith("Restore started")
                            load()
                            busy = false
                        }
                    },
                ) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { askRestore = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * The job itself: what it is doing now, and the answer to "did it work".
 *
 * Internal rather than private so the page can be rendered whole in a snapshot
 * test: the cards are individually fine and still manage to read as a mess when
 * they are stacked, and only the stack is what the operator sees.
 */
@Composable
internal fun RunCard(
    status: BackupStatus?,
    now: Long,
    busy: Boolean,
    onStart: () -> Unit,
    onRefresh: () -> Unit,
) {
    SectionCard("Backup", "The engine keeps its own tree on a Hugging Face bucket.") {
        val s = status
        if (s == null) {
            Text("Reading the engine's backup state…", style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        StatusBanner(
            when {
                s.running -> "A ${s.operation} is running on the engine"
                s.configured -> "Ready — ${s.bucket} · ${s.prefix} · root ${s.root}"
                // The tree is not on this machine yet, which stops a backup and
                // is precisely what a restore is for.
                s.restorable -> "A restore can still run; backing up needs: " +
                    s.needs.joinToString(", ")
                else -> "Not ready — still needs: " +
                    s.needs.joinToString(", ").ifBlank { "the hub token and the bucket" }
            },
            error = !s.running && !s.configured,
        )
        Text(
            "hub token: " + if (s.tokenTail.isBlank()) "not stored" else "stored (ends …${s.tokenTail})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (s.running) ProgressBlock(s, now)
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = onStart,
                enabled = !busy && !s.running && s.configured,
            ) { Text("Back up now") }
            OutlinedButton(onClick = onRefresh, enabled = !busy) { Text("Refresh") }
        }
        if (s.running) {
            Text(
                "The engine keeps writing if you leave this page or close the app — " +
                    "come back to see where it got to.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The progress of the run in flight. Bytes lead and file counts follow, because
 * a backup's counts step in whole commits rather than one file at a time, and a
 * scan has no counts to give at all until it finishes.
 */
@Composable
private fun ProgressBlock(s: BackupStatus, now: Long) {
    val fraction = when {
        s.bytesTotal > 0L -> (s.bytesDone.toFloat() / s.bytesTotal.toFloat()).coerceIn(0f, 1f)
        s.total > 0 -> (s.done.toFloat() / s.total.toFloat()).coerceIn(0f, 1f)
        else -> 0f
    }
    val measured = s.bytesTotal > 0L || s.total > 0
    Text(
        buildString {
            append(s.phase.ifBlank { s.operation })
            if (s.total > 0) append(" · ").append(humanCount(s.done)).append(" of ").append(humanCount(s.total)).append(" files")
            if (s.bytesTotal > 0L) append(" · ")
                .append(AttachmentRef.humanSize(s.bytesDone))
                .append(" of ").append(AttachmentRef.humanSize(s.bytesTotal))
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    if (measured) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        Text(
            "${(fraction * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        Text(
            "still measuring the tree — no totals yet",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (s.message.isNotBlank()) {
        Text(
            s.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // The engine stamps when a run began on its own clock; only an elapsed time
    // can be wrong from phone drift, and never a total the engine itself gave.
    val elapsed = if (s.startedAt <= 0L) 0L else (now / 1000L - s.startedAt).coerceAtLeast(0L)
    // What is left comes from the rate so far, the only number this side of the
    // request has. It is a guess, not a deadline: an upload that speeds up once
    // the bucket warms, or a tree whose files arrive in uneven sizes, moves it,
    // so it waits until enough has gone by to have a rate worth dividing by.
    val left = if (measured && fraction >= 0.05f && elapsed > 20L) {
        (elapsed * (1f - fraction) / fraction).toLong()
    } else {
        0L
    }
    Text(
        buildString {
            append("running for ").append(humanDuration(elapsed))
            if (left > 0L) {
                append(" · roughly ").append(humanDuration(left)).append(" left at the rate so far")
            }
            append(" · started ").append(atUtc(s.startedAt))
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * What the last run to finish reported, spelled out. The engine keeps one of
 * these and forgets it on restart, so the reused counts and the paths it could
 * not read are shown while they are still being answered.
 */
@Composable
internal fun RunResultCard(run: BackupRun) {
    val restore = run.operation == "restore"
    SectionCard(
        if (run.ok) "Last ${run.operation} succeeded" else "Last ${run.operation} failed",
        run.report?.name?.ifBlank { null }
            ?: "The engine has one result slot and clears it when it restarts",
    ) {
        StatusBanner(
            if (run.ok) {
                val report = run.report
                "${run.operation} ${report?.name.orEmpty()} — " +
                    "${humanCount(report?.files ?: 0)} files in " +
                    "${humanDuration((report?.durationMs ?: 0L) / 1000)}"
            } else {
                "failed: ${run.error.ifBlank { "the engine gave no reason" }}"
            },
            error = !run.ok,
        )
        if (!run.ok && run.report == null) {
            Text(
                "Nothing was reported beyond that. A failed run leaves no manifest, " +
                    "so what is in the bucket is still what worked before.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        val r = run.report ?: return@SectionCard
        DetailRow("files", humanCount(r.files))
        if (r.dirs > 0) DetailRow("directories", humanCount(r.dirs))
        if (r.symlinks > 0) DetailRow("symlinks", humanCount(r.symlinks))
        DetailRow("tree size", AttachmentRef.humanSize(r.bytes))
        if (!restore) {
            DetailRow("uploaded this run", AttachmentRef.humanSize(r.uploadedBytes))
            DetailRow("files already current", humanCount(r.filesReused))
            if (r.objectsReused > 0) {
                DetailRow("objects already in the bucket", humanCount(r.objectsReused))
            }
        } else {
            DetailRow("already correct", humanCount(r.unchanged))
        }
        if (r.deletedManifests > 0) {
            DetailRow("replaced", "${r.deletedManifests} previous manifest(s)")
        }
        if (r.root.isNotBlank()) DetailRow("root", r.root)
        if (r.bucket.isNotBlank()) DetailRow("bucket", r.bucket)
        DetailRow("took", humanDuration(r.durationMs / 1000))
        DetailRow("effective throughput", humanRate(r.bytes, r.durationMs))
        if (r.skipped.isNotEmpty()) {
            HorizontalDivider()
            // A path that could not be read is missing from the backup and the
            // engine only says so in a list, so it is shown rather than counted.
            Text(
                "not readable, so not backed up (${humanCount(r.skipped.size)})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            r.skipped.take(20).forEach { path ->
                Text(
                    path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (r.skipped.size > 20) {
                Text("…and ${r.skipped.size - 20} more",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (r.failed.isNotEmpty()) {
            HorizontalDivider()
            // A restore that went through the manifest and could not write some
            // of it ends with the tree short by exactly these paths. Counting
            // them would leave the question of which, and the reason each one
            // carries is what tells a missing object apart from a full disk.
            Text(
                "could not be restored (${r.failed.size})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            r.failed.take(20).forEach { problem ->
                Text(
                    problem,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (r.failed.size > 20) {
                Text("…and ${r.failed.size - 20} more",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The bucket holds exactly one backup: the tree as of the last successful run.
 * Restoring brings that tree back; there is no history to choose from, which
 * is what makes a restore here simple enough to trust on a broken machine.
 */
@Composable
internal fun RestoreCard(
    status: BackupStatus,
    busy: Boolean,
    onRestore: () -> Unit,
) {
    val restoreRoot = status.lastBackup?.takeIf { it.ok }?.report?.root
        ?.ifBlank { status.root } ?: status.root
    SectionCard(
        "Restore",
        "The bucket holds one backup — the tree as the last successful run left it.",
    ) {
        Text(
            "A restore writes the saved tree under $restoreRoot on the engine, and leaves " +
                "files it does not name where they are.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val restorable = status.restorable && !status.running
        FilledTonalButton(
            onClick = onRestore,
            enabled = busy.not() && restorable,
        ) { Text("Restore the backup") }
        if (!restorable && !status.running) {
            Text(
                if (status.configured) "Idle." else "A restore still needs: " +
                    status.needsRestore.joinToString(", ").ifBlank { "the hub token and the bucket" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * The file and folder paths explicitly selected for backup.
 */
@Composable
fun ScopeCard(
    status: BackupStatus?,
    includes: List<String>,
    busy: Boolean,
    onEditScope: () -> Unit,
    onSave: () -> Unit,
) {
    val reportsScope = status?.reportsScope == true
    SectionCard(
        "What gets backed up",
        if (reportsScope) "Only the files and folders you explicitly select."
        else "Stored on the engine, in D1.",
    ) {
        if (reportsScope) {
            PickerRow(
                label = "Manage backup files and folders",
                value = if (includes.isEmpty()) "Nothing selected"
                    else "${includes.size} rules",
                hint = "A checked folder includes its contents. A partial checkbox marks excluded items inside it.",
                onPick = onEditScope,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave, enabled = !busy) { Text("Save selection") }
            }
        } else {
            DetailRow("root", status?.root.orEmpty().ifBlank { "not set" })
            Text(
                "This engine does not support selecting backup files and folders. Update the engine to use the picker.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Where the tree goes: the hub token, the bucket and the prefix it owns there. */
@Composable
internal fun BucketCard(
    status: BackupStatus?,
    token: String,
    onToken: (String) -> Unit,
    bucket: String,
    onBucket: (String) -> Unit,
    prefix: String,
    onPrefix: (String) -> Unit,
    showToken: Boolean,
    onToggleToken: () -> Unit,
    busy: Boolean,
    onSave: () -> Unit,
) {
    SectionCard("Where it goes", "Stored on the engine, in D1. The hub token never comes back out.") {
        LabelledField(
            value = token,
            onValueChange = onToken,
            label = "HF hub token",
            hint = if (status?.tokenTail.isNullOrBlank()) {
                "Not stored yet — paste a write token from huggingface.co/settings/tokens"
            } else {
                "Stored (ends …${status?.tokenTail}); leave blank to keep it"
            },
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            trailing = {
                IconButton(onClick = onToggleToken) {
                    Icon(
                        if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showToken) "Hide token" else "Show token",
                    )
                }
            },
        )
        LabelledField(
            value = bucket,
            onValueChange = onBucket,
            label = "Bucket",
            placeholder = "namespace/name",
            hint = "The Hugging Face storage bucket, as its API spells it",
        )
        LabelledField(
            value = prefix,
            onValueChange = onPrefix,
            label = "Key prefix",
            hint = "The part of the bucket this backup owns; nothing outside it is touched",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, enabled = !busy) { Text("Save bucket settings") }
        }
    }
}

@Composable
internal fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

/** Persistent record of the last completed, successful backup. */
@Composable
internal fun LastBackupStrip(lastBackup: BackupRun?) {
    val successful = lastBackup?.takeIf { it.ok && it.finishedAt > 0 }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Last successful backup",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                successful?.let {
                    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
                        .format(Date(it.finishedAt * 1000L))
                } ?: "No successful backup recorded",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        successful?.report?.let { report ->
            Text(
                "${report.files} files",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "340s" is a worse answer than "5m 40s", and a run spans both scales. */
internal fun humanDuration(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${(seconds % 60)}s"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

/** Effective tree throughput, using the bytes represented by the completed run. */
internal fun humanRate(bytes: Long, durationMs: Long): String {
    if (bytes <= 0L || durationMs <= 0L) return "—"
    val perSecond = (bytes.toDouble() * 1000.0 / durationMs).toLong()
    return "${AttachmentRef.humanSize(perSecond)}/s"
}

/** Grouped digits: 47930 and 47,930 are the same count but not the same read. */
internal fun humanCount(value: Int): String = String.format(java.util.Locale.US, "%,d", value)

/** A bucket stamp is only useful in a timezone the reader can name. */
internal fun atUtc(unixSeconds: Long): String {
    if (unixSeconds <= 0L) return "—"
    val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", java.util.Locale.US)
    format.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return format.format(java.util.Date(unixSeconds * 1000L))
}
