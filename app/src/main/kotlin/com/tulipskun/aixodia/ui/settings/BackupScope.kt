package com.tulipskun.aixodia.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tulipskun.aixodia.data.model.AttachmentRef
import com.tulipskun.aixodia.data.remote.AutoBackupStatus
import com.tulipskun.aixodia.data.remote.BackupPreview
import com.tulipskun.aixodia.data.remote.BackupRun


/**
 * What the scope on screen would cover, counted on the engine.
 *
 * The comparison with the last run is the point of the card: a bucket holds one
 * backup, so a scope that quietly drops half the tree is only visible here — by
 * the time it matters, the wider backup it replaced is gone.
 */
@Composable
fun PreviewCard(
    preview: BackupPreview?,
    error: String,
    busy: Boolean,
    enabled: Boolean,
    lastRun: BackupRun?,
    onPreview: () -> Unit,
) {
    SectionCard(
        "What this would back up",
        "Counted by the engine, before anything is uploaded. Nothing here is saved.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onPreview, enabled = !busy && enabled) {
                Text(if (preview == null) "Preview this scope" else "Measure it again")
            }
            if (busy) {
                Text(
                    "walking the tree…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (busy && preview == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) StatusBanner(error, error = true)
        val p = preview ?: return@SectionCard
        val previous = lastRun?.report?.takeIf { lastRun.operation == "backup" && lastRun.ok }
        StatusBanner(
            buildString {
                append(humanCount(p.files)).append(" files · ").append(AttachmentRef.humanSize(p.bytes))
                if (previous != null && previous.files > 0) {
                    append(" (last backup: ").append(humanCount(previous.files))
                        .append(" files · ").append(AttachmentRef.humanSize(previous.bytes)).append(")")
                }
            },
            error = previous != null && previous.files > 0 && p.files * 2 < previous.files,
        )
        DetailRow("directories", humanCount(p.dirs))
        if (p.symlinks > 0) DetailRow("symlinks", humanCount(p.symlinks))
        DetailRow("root", p.root)
        if (p.folders.isNotEmpty()) {
            HorizontalDivider()
            Text(
                "Where the bytes are",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            p.folders.forEach { folder ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(
                        folder.name,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                    Text(
                        "${humanCount(folder.files)} files · ${AttachmentRef.humanSize(folder.bytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (p.skipped > 0) {
            HorizontalDivider()
            Text(
                "not readable, so it would not be backed up (${humanCount(p.skipped)})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            p.skippedExamples.forEach { path ->
                Text(
                    path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            "A narrower selection updates only the folders you chose. Previously " +
                "backed-up files outside that selection remain in the restore set. " +
                "Folders explicitly excluded are removed from the next backup.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The idle-run throttle, which the engine already runs and configures. */
@Composable
fun AutoBackupCard(
    auto: AutoBackupStatus?,
    busy: Boolean,
    onSave: (enabled: Boolean, idleSecs: Long, cpuThreshold: Double, cooldownSecs: Long) -> Unit,
) {
    SectionCard(
        "Automatic backup",
        "Runs when the engine has been idle and quiet, so a backup never competes with work.",
    ) {
        val a = auto
        if (a == null) {
            Text(
                "This engine does not report an automatic backup, so there is nothing " +
                    "to set here. Update the engine to get one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        // Keyed on what the engine is *configured* to do, not on the object it
        // answers with: the streak and the CPU reading move on every poll, and a
        // key that changed with them would throw away a choice the operator made
        // between the poll and the Save button.
        val configured = arrayOf<Any?>(a.enabled, a.idleSecs, a.cpuThreshold, a.cooldownSecs)
        var enabled by remember(*configured) { mutableStateOf(a.enabled) }
        var idleSecs by remember(*configured) { mutableStateOf(a.idleSecs) }
        var cpu by remember(*configured) { mutableStateOf(a.cpuThreshold) }
        var cooldown by remember(*configured) { mutableStateOf(a.cooldownSecs) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = enabled, onCheckedChange = { enabled = it })
            Text(
                if (enabled) "On — the engine runs it by itself" else "Off — backups start from this page",
                Modifier.padding(start = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        ChipRow(
            "Idle for",
            options = listOf(300L, 600L, 900L, 1800L),
            selected = idleSecs,
            name = { "${it / 60} minutes" },
            onPick = { idleSecs = it },
        )
        ChipRow(
            "CPU under",
            options = listOf(5.0, 10.0, 25.0, 40.0),
            selected = cpu,
            name = { "${it.toInt()}%" },
            onPick = { cpu = it },
        )
        ChipRow(
            "At most one every",
            options = listOf(1800L, 3600L, 10800L, 21600L),
            selected = cooldown,
            name = { if (it < 3600) "${it / 60} min" else "${it / 3600} h" },
            onPick = { cooldown = it },
        )
        HorizontalDivider()
        if (a.cpuHistory.isNotEmpty()) {
            Text("CPU usage · last 15 minutes", style = MaterialTheme.typography.titleSmall)
            val gridColor = MaterialTheme.colorScheme.outlineVariant
            val graphColor = MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxWidth().height(112.dp)) {
            val top = 6.dp.toPx()
            val bottom = size.height - 6.dp.toPx()
            val chartHeight = bottom - top
            for (line in 0..4) {
                val y = top + chartHeight * line / 4f
                drawLine(
                    color = gridColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            val values = a.cpuHistory.takeLast(60)
            if (values.size >= 2) {
                val path = Path()
                values.forEachIndexed { index, value ->
                    val x = size.width * index / (values.size - 1).toFloat()
                    val y = bottom - (value.toFloat().coerceIn(0f, 100f) / 100f) * chartHeight
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = graphColor,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
            Text(
                if (a.cpuHistory.size < 2) "Collecting CPU samples…" else "0% — 100%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!a.restoreReady) {
            StatusBanner(
                "Auto-backup is blocked until a restore completes successfully. This prevents an incomplete local tree from replacing the existing backup.",
                error = true,
            )
        }
        Text(
            buildString {
                append("idle streak ${a.idleStreakSecs}s · cpu now ${fmt1(a.cpuCurrent)}%")
                append(if (a.lastAutoBackupAt > 0) " · last automatic run ${atUtc(a.lastAutoBackupAt)}" else " · no automatic run yet")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { onSave(enabled, idleSecs, cpu, cooldown) },
            enabled = !busy,
        ) { Text("Save automatic backup") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(
    label: String,
    options: List<T>,
    selected: T,
    name: (T) -> String,
    onPick: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        // A plain Row pushed the fourth choice off the screen on a phone — the
        // option was there and invisible, which is worse than not offering it.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onPick(option) },
                    label = { Text(name(option)) },
                )
            }
        }
    }
}

/** One decimal, because "10.0 % of a machine" reads better than "10.0". */
private fun fmt1(value: Double): String = (Math.round(value * 10) / 10.0).toString()
