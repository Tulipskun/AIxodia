package com.tulipskun.aixodia.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import com.tulipskun.aixodia.data.remote.BackupRow
import com.tulipskun.aixodia.data.remote.BackupRun
import com.tulipskun.aixodia.data.remote.BackupStatus
import com.tulipskun.aixodia.data.remote.HistoryApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    var rows by remember { mutableStateOf<List<BackupRow>>(emptyList()) }
    // Which of the two reads failed is kept apart: a daemon that is not there
    // used to look exactly like a bucket with nothing in it.
    var readError by remember { mutableStateOf("") }
    var listRead by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var msgIsError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var restoreTarget by remember { mutableStateOf<BackupRow?>(null) }
    // Advanced once a second only while a job runs, so the elapsed clock moves
    // without the page polling the engine any harder.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    // Config fields, filled from the engine once per visit: refilling on every
    // poll would overwrite whatever the reader is halfway through typing.
    var token by rememberSaveable { mutableStateOf("") }
    var bucket by rememberSaveable { mutableStateOf("") }
    var prefix by rememberSaveable { mutableStateOf("") }
    var retention by rememberSaveable { mutableStateOf("") }
    var excludesText by rememberSaveable { mutableStateOf("") }
    var gc by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }
    var filled by remember { mutableStateOf(false) }

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
                retention = s.retention.toString()
                gc = s.gc
                excludesText = s.excludes.orEmpty().joinToString("\n")
            }
        }
        val listed = runCatchingCancellable { history.backups() }.getOrNull()
        // A read that did not answer leaves the list already on screen rather
        // than emptying it: an outage must not look like an empty bucket.
        listRead = listed != null
        if (listed != null) rows = listed
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup & restore") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { pad ->
        // As wide as Settings and scrolled the same way, so the two pages do not
        // reflow differently when the reader moves between them.
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .widthIn(max = 760.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
        if (readError.isNotBlank()) StatusBanner(readError, error = true)
        if (msg.isNotBlank()) StatusBanner(msg, msgIsError)

        RunCard(
            status = s,
            rowsKnown = listRead,
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

        val run = s?.run
        if (run != null) RunResultCard(run)

        if (s != null) {
            BucketCard(
                rows = rows,
                root = s.root,
                busy = busy,
                onRestore = { restoreTarget = it },
            )
        }

        ConfigCard(
            status = s,
            token = token, onToken = { token = it },
            bucket = bucket, onBucket = { bucket = it },
            prefix = prefix, onPrefix = { prefix = it },
            retention = retention, onRetention = { retention = it },
            gc = gc, onGc = { gc = it },
            excludesText = excludesText, onExcludes = { excludesText = it },
            showToken = showToken, onToggleToken = { showToken = !showToken },
            busy = busy,
            onSave = {
                val keep = retention.trim().toIntOrNull()
                if (keep == null || keep < 1) {
                    msg = "Retention must be a whole number of manifests, at least 1"
                    msgIsError = true
                    return@ConfigCard
                }
                // An engine that does not report its exclusions is not asked to
                // change them: the list on screen would be invented, and a wrong
                // backup scope is the one mistake a backup tool cannot take back.
                val list = s?.excludes?.let {
                    excludesText.split('\n').map { line -> line.trim() }.filter { line -> line.isNotBlank() }
                }
                busy = true; msg = ""
                scope.launch {
                    val out = history.saveBackupConfig(token, bucket, prefix, keep, gc, list)
                    msg = out
                    msgIsError = !out.startsWith("Backup settings saved")
                    // The engine is the authority on what it now stores.
                    filled = false
                    load()
                    busy = false
                }
            },
        )
        }
    }

    val target = restoreTarget
    if (target != null && s != null) {
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "${target.name} — ${target.files} files, " +
                        "${AttachmentRef.humanSize(target.bytes)}, from ${atUtc(target.createdAt)}.\n\n" +
                        "Restoring overwrites files under ${s.root} on the engine with what " +
                        "this backup holds. Files it does not name are left where they are. " +
                        "This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        restoreTarget = null
                        busy = true; msg = ""
                        scope.launch {
                            val out = history.backupRestore(target.name)
                            msg = out
                            msgIsError = !out.startsWith("Restore started")
                            load()
                            busy = false
                        }
                    },
                ) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { restoreTarget = null }) { Text("Cancel") }
            },
        )
    }
}

/** The job itself: what it is doing now, and the answer to "did it work". */
@Composable
private fun RunCard(
    status: BackupStatus?,
    rowsKnown: Boolean,
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
        if (!rowsKnown) {
            Text(
                "The bucket list was not read — what is shown below is not the whole store",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
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
            if (s.total > 0) append(" · ").append(s.done).append(" of ").append(s.total).append(" files")
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
    Text(
        "running for ${humanDuration(elapsed)} · started ${atUtc(s.startedAt)}",
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
private fun RunResultCard(run: BackupRun) {
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
                    "${report?.files ?: 0} files in ${humanDuration((report?.durationMs ?: 0L) / 1000)}"
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
        DetailRow("files", r.files.toString())
        if (r.dirs > 0) DetailRow("directories", r.dirs.toString())
        if (r.symlinks > 0) DetailRow("symlinks", r.symlinks.toString())
        DetailRow("tree size", AttachmentRef.humanSize(r.bytes))
        if (!restore) {
            DetailRow("uploaded this run", AttachmentRef.humanSize(r.uploadedBytes))
            DetailRow("files already current", r.filesReused.toString())
            if (r.objectsReused > 0) DetailRow("objects already in the bucket", r.objectsReused.toString())
        } else {
            DetailRow("already correct", r.unchanged.toString())
        }
        if (r.deletedManifests > 0 || r.deletedObjects > 0) {
            DetailRow(
                "pruned",
                "${r.deletedManifests} manifests · ${r.deletedObjects} objects",
            )
        }
        if (r.root.isNotBlank()) DetailRow("root", r.root)
        if (r.bucket.isNotBlank()) DetailRow("bucket", r.bucket)
        DetailRow("took", humanDuration(r.durationMs / 1000))
        if (r.skipped.isNotEmpty()) {
            HorizontalDivider()
            // A path that could not be read is missing from the backup and the
            // engine only says so in a list, so it is shown rather than counted.
            Text(
                "not readable, so not backed up (${r.skipped.size})",
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
    }
}

/** The manifests in the bucket, each one restorable by name. */
@Composable
private fun BucketCard(
    rows: List<BackupRow>,
    root: String,
    busy: Boolean,
    onRestore: (BackupRow) -> Unit,
) {
    SectionCard(
        "In the bucket",
        "Each manifest is a whole tree you can go back to. Newest first.",
    ) {
        if (rows.isEmpty()) {
            Text(
                if (busy) "Reading…" else "No manifest in the bucket yet — run a backup first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        rows.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !busy) { onRestore(row) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(row.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${atUtc(row.createdAt)} · ${row.files} files · " +
                            AttachmentRef.humanSize(row.bytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { onRestore(row) }, enabled = !busy) {
                    Text("Restore")
                }
            }
            HorizontalDivider()
        }
        Text(
            "A restore overwrites files under $root and leaves the ones this " +
                "manifest does not name where they are.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConfigCard(
    status: BackupStatus?,
    token: String,
    onToken: (String) -> Unit,
    bucket: String,
    onBucket: (String) -> Unit,
    prefix: String,
    onPrefix: (String) -> Unit,
    retention: String,
    onRetention: (String) -> Unit,
    gc: Boolean,
    onGc: (Boolean) -> Unit,
    excludesText: String,
    onExcludes: (String) -> Unit,
    showToken: Boolean,
    onToggleToken: () -> Unit,
    busy: Boolean,
    onSave: () -> Unit,
) {
    SectionCard("Backup settings", "Stored on the engine, in D1. The hub token never comes back out.") {
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
        LabelledField(
            value = retention,
            onValueChange = onRetention,
            label = "Manifests kept",
            hint = "Runs older than this are pruned; each one is a tree you can restore",
        )
        FilterChip(
            selected = gc,
            onClick = { onGc(!gc) },
            label = { Text("Delete objects no kept manifest needs") },
        )
        HorizontalDivider()
        if (status?.reportsExcludes == true) {
            // One pattern per line, the same list the walker consults.
            LabelledField(
                value = excludesText,
                onValueChange = onExcludes,
                label = "Leave these out",
                hint = "One pattern per line: a bare name skips that folder anywhere, " +
                    "* and ? match a name, a pattern with / matches the whole path. " +
                    "Clearing the list means nothing is left out, this engine's own " +
                    "defaults included.",
                singleLine = false,
                minLines = 4,
            )
        } else {
            Text(
                "This engine does not report what it leaves out, so the scope is " +
                    "not shown and will not be changed from here. Update the " +
                    "engine to edit it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, enabled = !busy) { Text("Save backup settings") }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
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

/** "340s" is a worse answer than "5m 40s", and a run spans both scales. */
private fun humanDuration(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${(seconds % 60)}s"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

/** A bucket stamp is only useful in a timezone the reader can name. */
private fun atUtc(unixSeconds: Long): String {
    if (unixSeconds <= 0L) return "—"
    val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", java.util.Locale.US)
    format.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return format.format(java.util.Date(unixSeconds * 1000L))
}
