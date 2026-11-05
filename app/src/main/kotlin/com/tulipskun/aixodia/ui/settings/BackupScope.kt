package com.tulipskun.aixodia.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.tulipskun.aixodia.data.model.AttachmentRef
import com.tulipskun.aixodia.data.remote.AutoBackupStatus
import com.tulipskun.aixodia.data.remote.BackupBrowse
import com.tulipskun.aixodia.data.remote.BackupPreview
import com.tulipskun.aixodia.data.remote.BackupRun
import com.tulipskun.aixodia.data.remote.BrowseEntry
import com.tulipskun.aixodia.data.remote.HistoryApi

/**
 * The parts of the backup page that replace typing with choosing: a folder
 * browser, the allowlist sheet it feeds, the preview that answers "what would
 * this cover", and the automatic backup's own card.
 *
 * They live apart from `BackupScreen` because that page already holds the run,
 * its progress and its outcome, and a scope is a different question with its own
 * state — a directory listing the engine answers one folder at a time.
 */

/** `path` is `dir` itself or inside it, compared as text on canonical paths. */
internal fun isUnder(path: String, dir: String): Boolean {
    val head = dir.trimEnd('/')
    if (head.isEmpty()) return true
    return path == head || path.startsWith("$head/")
}

/**
 * The allowlist entry for a browsed folder, relative to the root.
 *
 * The engine matches includes against paths *from* the root, so the absolute
 * path the browser walked to is not what gets stored. Picking the root itself
 * answers empty rather than "." — an empty allowlist means the whole tree, and
 * there is no other way to say it.
 */
internal fun relativise(root: String, path: String): String {
    val head = root.trimEnd('/')
    if (path == head) return ""
    // A path that is merely *prefixed* by the root ("/rootbackup" under "/root") is
    // not inside it, and stripping the prefix anyway would invent an entry naming a
    // folder nobody picked. Such a path is handed back as it came, and the caller
    // refuses it: an allowlist entry is relative to the root or it is nothing.
    if (!path.startsWith("$head/")) return path
    return path.removePrefix("$head/")
}

/**
 * Whether `rel` is already inside something the operator listed, or the other way
 * round — picking `projects` when `projects/aixodia` is listed changes nothing but
 * the shape of the list, and picking the latter after the former would silently
 * narrow a scope the operator meant to widen.
 */
internal fun isAlreadyCovered(includes: List<String>, rel: String): Boolean = includes.any {
    rel == it || rel.startsWith("$it/") || "$it/".startsWith("$rel/")
}

/**
 * The folders the operator can tick the moment the sheet opens: the engine's own
 * children of the backup root.
 *
 * Directories only, and only those the walker may enter — a symlink or a folder on
 * another device would tick green and back up nothing, which is the one thing a
 * checkbox must never do. Sorting here rather than trusting the engine's order
 * keeps a tap from moving rows around under the finger.
 */
internal fun rootFolderNames(browse: BackupBrowse?): List<String> =
    browse?.entries.orEmpty()
        .filter { it.isDir && it.canEnter }
        .map { it.name }
        .sorted()

/** How a root folder relates to the current allowlist. */
internal enum class FolderPick { Off, Whole, Partial }

/**
 * [Partial] is a folder that was not picked itself but contains a pick — choosing
 * `projects/aixodia/app` does cover `projects`, and a checkbox that showed that
 * folder empty would be describing a backup it is not going to make.
 */
internal fun pickOf(includes: List<String>, name: String): FolderPick = when {
    includes.any { it == name } -> FolderPick.Whole
    includes.any { it.startsWith("$name/") } -> FolderPick.Partial
    else -> FolderPick.Off
}

/**
 * One tap on one folder's checkbox.
 *
 * A folder that was only partly in turns *fully* in: the operator asked for more,
 * and keeping the narrower entries beside it would look like a different answer
 * than the one given. A folder that was in whole turns off, and takes whatever was
 * listed inside it along — those paths only ever meant "this part of the tree"
 * while the part was ticked. Sorted either way, so the list does not shuffle
 * itself depending on which way the tap went.
 */
internal fun toggleFolder(includes: List<String>, name: String): List<String> {
    val without = includes.filter { it != name && !it.startsWith("$name/") }
    return (if (pickOf(includes, name) == FolderPick.Whole) without else without + name).sorted()
}

/**
 * The remove button, which is not the same question as a checkbox: it takes the
 * folder and whatever was listed inside it out, and does not put the folder's own
 * name back the way a tap on a partly-ticked row would.
 */
internal fun dropPick(includes: List<String>, name: String): List<String> =
    includes.filter { it != name && !it.startsWith("$name/") }

/** A bare exclusion names a folder component; a path exclusion names one folder under the root. */
internal fun toggleExcludedFolder(excludes: List<String>, name: String): List<String> =
    if (name in excludes) excludes.filterNot { it == name }.sorted()
    else (excludes + name).distinct().sorted()

/**
 * Picks whose first component is not in the current listing: a folder renamed or
 * deleted on the engine after it was chosen. They still shape the backup, so they
 * stay visible and removable rather than vanishing because a directory read
 * happened not to name them.
 */
internal fun stalePicks(includes: List<String>, folders: List<String>): List<String> {
    val live = folders.toSet()
    return includes.map { it.substringBefore('/') }
        .distinct()
        .filterNot { it in live }
        .sorted()
}

/**
 * One directory on the engine, walked a folder at a time.
 *
 * [bound] confines the walk when the picker is choosing *inside* the root (a
 * scope); choosing the root itself is unbounded, because the point is that the
 * tree may live anywhere on that machine. A symlinked folder and one on another
 * filesystem are listed but not enterable — the walker refuses both, so offering
 * them would let a scope be chosen that silently backs up nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderBrowserSheet(
    history: HistoryApi,
    start: String,
    title: String,
    pickLabel: String,
    bound: String?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var path by remember { mutableStateOf(start.ifBlank { "/" }) }
    var listing by remember { mutableStateOf<BackupBrowse?>(null) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var typed by remember { mutableStateOf("") }
    var showTyped by remember { mutableStateOf(false) }
    // Asking for the same folder again has to re-read it, and a key that did not
    // change would not: the counter is what makes "Reload" mean something.
    var nonce by remember { mutableStateOf(0) }

    LaunchedEffect(path, nonce) {
        loading = true
        val read = history.backupBrowse(path)
        listing = read.value
        error = read.error
        loading = false
    }

    val outside = bound != null && !isUnder(path, bound)
    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                path,
                style = MaterialTheme.typography.bodySmall,
                color = if (outside) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            if (outside) {
                StatusBanner("This is outside $bound — the backup's root. Move back in to pick a folder.", error = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val parent = listing?.parent.orEmpty()
                OutlinedButton(
                    onClick = { if (parent.isNotBlank()) path = parent },
                    enabled = parent.isNotBlank() && (bound == null || isUnder(parent, bound)),
                ) { Text("Up") }
                OutlinedButton(onClick = { nonce++ }, enabled = !loading) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text("Reload")
                }
                TextButton(onClick = { showTyped = !showTyped }) {
                    Text(if (showTyped) "Browse instead" else "Type a path")
                }
            }
            if (showTyped) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LabelledField(
                        value = typed,
                        onValueChange = { typed = it },
                        label = "Absolute path on the engine",
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { if (typed.isNotBlank()) path = typed.trim() },
                        enabled = typed.isNotBlank(),
                    ) { Text("Go") }
                }
            }
            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (error.isNotBlank()) StatusBanner(error, error = true)
            val rows = listing?.entries.orEmpty()
            if (!loading && rows.isEmpty() && error.isBlank()) {
                Text(
                    "Nothing to show — this folder is empty.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(rows, key = { it.path }) { entry -> BrowseRow(entry) { path = entry.path } }
            }
            listing?.takeIf { it.truncated }?.let {
                Text(
                    "This folder holds more than the picker lists. Type a deeper path " +
                        "to reach the rest.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onPick(path) }, enabled = !outside && !loading) { Text(pickLabel) }
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun BrowseRow(entry: BrowseEntry, onEnter: () -> Unit) {
    val canWalk = entry.isDir && entry.canEnter
    Surface(
        onClick = { if (canWalk) onEnter() },
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (entry.isDir) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                tint = if (canWalk) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(entry.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                val why = when {
                    canWalk -> ""
                    !entry.isDir && !entry.isSymlink -> "a file — it is backed up with its folder"
                    entry.isSymlink -> "a link — the walker does not follow it"
                    else -> "another filesystem — the walker does not cross it"
                }
                if (why.isNotBlank()) {
                    Text(
                        why,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The scope itself: the engine's folders to tick, and the switch that means
 * "everything".
 *
 * The tick list is the whole question — "which folders do you want" — so it is
 * what the sheet opens on, filled from one read of the root rather than waiting
 * for the operator to go hunting through a browser. It starts unticked and stays
 * that way, because an empty allowlist is the whole tree while a seeded one is a
 * snapshot of today's folders — and tomorrow's folder would be silently outside it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScopeSheet(
    root: String,
    includes: List<String>,
    excludes: List<String>,
    folders: List<String>,
    foldersLoading: Boolean,
    foldersError: String,
    foldersTruncated: Boolean,
    onToggle: (String) -> Unit,
    onRemove: (String) -> Unit,
    onAddFolder: () -> Unit,
    onToggleExclude: (String) -> Unit = {},
    onRemoveExclude: (String) -> Unit = {},
    onAddExcludeFolder: () -> Unit = {},
    onEverything: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        ScopeSheetContent(
            root = root,
            includes = includes,
            excludes = excludes,
            folders = folders,
            foldersLoading = foldersLoading,
            foldersError = foldersError,
            foldersTruncated = foldersTruncated,
            onToggle = onToggle,
            onRemove = onRemove,
            onAddFolder = onAddFolder,
            onToggleExclude = onToggleExclude,
            onRemoveExclude = onRemoveExclude,
            onAddExcludeFolder = onAddExcludeFolder,
            showExcludePicker = true,
            onEverything = onEverything,
            onDismiss = onDismiss,
        )
    }
}

/**
 * The sheet's body, apart from the sheet.
 *
 * A bottom sheet is its own window, which the offscreen renderer cannot show; the
 * checklist is the part worth looking at, and keeping it separate is what lets a
 * snapshot test answer "does one tap on a folder read the way it should".
 */
@Composable
fun ScopeSheetContent(
    root: String,
    includes: List<String>,
    excludes: List<String>,
    folders: List<String>,
    foldersLoading: Boolean,
    foldersError: String,
    foldersTruncated: Boolean,
    onToggle: (String) -> Unit,
    onRemove: (String) -> Unit,
    onAddFolder: () -> Unit,
    onToggleExclude: (String) -> Unit = {},
    onAddExcludeFolder: () -> Unit = {},
    showExcludePicker: Boolean = false,
    onEverything: () -> Unit,
    onDismiss: () -> Unit,
) {
    val everything = includes.isEmpty()
    val stale = stalePicks(includes, folders)
    Column(
        Modifier.fillMaxWidth()
            .heightIn(max = 620.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("What to back up", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = everything,
                onCheckedChange = { if (!everything) onEverything() },
            )
            Column(Modifier.padding(start = 10.dp)) {
                Text(
                    "Back up everything",
                    style = MaterialTheme.typography.bodyLarge,
                    // bodyLarge's line height is tighter than this face's ascent, and
                    // the tops of the tall letters were being cut where the row is
                    // only as tall as its own text.
                    modifier = Modifier.padding(top = 3.dp),
                )
                Text(
                    "the whole of $root, and anything created in it later",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
        Text(
            if (everything) {
                "Nothing is ticked, so this backs up all of it. Tick one folder to " +
                    "narrow the backup to just the folders you tick."
            } else {
                "Unticked folders are not in this backup. Folders created later are " +
                    "not either, until you tick them."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            foldersLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            folders.isEmpty() -> Text(
                if (foldersError.isBlank()) {
                    "$root holds no folders the engine may walk into."
                } else {
                    "$foldersError — use \"browse the engine\" below to reach a folder " +
                        "by hand instead."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> folders.forEach { name ->
                if (foldersTruncated && name == folders.last()) {
                    Text(
                        "The engine stops listing a folder after 2000 entries, so this " +
                            "may not be all of them. Browse below to reach a deeper path.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                FolderCheckRow(
                    name = name,
                    pick = pickOf(includes, name),
                    nested = includes.filter { it.startsWith("$name/") },
                    onToggle = { onToggle(name) },
                    onDrop = onRemove,
                )
            }
        }
        if (stale.isNotEmpty()) {
            HorizontalDivider()
            Text(
                "Picked earlier, no longer in $root",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            stale.forEach { name ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Text(name, Modifier.weight(1f).padding(start = 10.dp), maxLines = 1)
                    IconButton(onClick = { onRemove(name) }) {
                        Icon(Icons.Default.Close, contentDescription = "Stop backing up $name")
                    }
                }
            }
        }
        PickerRow(
            label = "Add a folder deeper inside",
            value = "browse the engine from $root",
            onPick = onAddFolder,
        )
        if (showExcludePicker) {
            HorizontalDivider()
            Text("Folders to leave out", style = MaterialTheme.typography.titleSmall)
            Text(
                "These folders stay excluded even when you back up everything or tick their parent.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (folders.isNotEmpty()) {
                folders.forEach { name ->
                    FolderExcludeRow(
                        name = name,
                        excluded = name in excludes,
                        onToggle = { onToggleExclude(name) },
                    )
                }
            }
            PickerRow(
                label = "Exclude a folder deeper inside",
                value = "browse inside $root",
                onPick = onAddExcludeFolder,
            )
    
        }
        val otherRules = if (showExcludePicker) excludes.filterNot { it in folders } else excludes
        if (otherRules.isNotEmpty()) {
            HorizontalDivider()
            Text(
                if (showExcludePicker) "Other exclusion rules (${otherRules.size})" else "Still left out inside whatever you tick (${otherRules.size})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            otherRules.take(14).forEach { pattern ->
                if (showExcludePicker) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            pattern,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IconButton(onClick = { onRemoveExclude(pattern) }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove exclusion $pattern")
                        }
                    }
                } else {
                    Text(
                        pattern,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (otherRules.size > 14) {
                Text(
                    "…and ${otherRules.size - 14} more",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
        Button(onClick = onDismiss) { Text("Done") }
    }
}

/**
 * One folder of the root, as a question with three answers.
 *
 * A folder holding a narrower pick reads as ticked, because its files *are* going
 * into the backup — but the caption names the pick that got them there, so the row
 * tells the truth about the scope instead of claiming the whole folder.
 */
@Composable
private fun FolderCheckRow(
    name: String,
    pick: FolderPick,
    nested: List<String>,
    onToggle: () -> Unit,
    onDrop: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(onClickLabel = "Change whether $name is backed up", onClick = onToggle)
                .padding(horizontal = 10.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = pick != FolderPick.Off,
                onCheckedChange = null,
            )
            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                if (pick == FolderPick.Partial) {
                    Text(
                        "partly in — tap to take the whole folder",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                tint = if (pick == FolderPick.Off) MaterialTheme.colorScheme.outline
                else MaterialTheme.colorScheme.primary,
            )
        }
        // Each narrower pick stays removable on its own. Without this, choosing one
        // app inside a folder and then changing your mind about just that app means
        // either ticking the whole folder or clearing it.
        nested.forEach { entry ->
            Row(
                Modifier.fillMaxWidth().padding(start = 26.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    entry.removePrefix("$name/"),
                    Modifier.weight(1f).padding(start = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                IconButton(onClick = { onDrop(entry) }, modifier = Modifier.height(30.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Stop backing up $entry",
                        modifier = Modifier.height(16.dp),
                    )
                }
            }
        }
    }
}


/** A folder can be explicitly excluded without writing a glob by hand. */
@Composable
private fun FolderExcludeRow(
    name: String,
    excluded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(
                onClickLabel = if (excluded) "Include $name in backup" else "Exclude $name from backup",
                onClick = onToggle,
            )
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = excluded, onCheckedChange = null)
        Text(
            name,
            Modifier.weight(1f).padding(start = 6.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Icon(
            Icons.Default.Folder,
            contentDescription = null,
            tint = if (excluded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
        )
    }
}

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
            "A backup replaces the one tree the bucket holds. A restore always " +
                "restores everything that tree names — narrowing this scope " +
                "narrows the next backup, never what a restore can put back.",
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
