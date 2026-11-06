package com.tulipskun.aixodia.ui.snapshots

import app.cash.paparazzi.Paparazzi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tulipskun.aixodia.data.remote.AutoBackupStatus
import com.tulipskun.aixodia.data.remote.BackupPreview
import com.tulipskun.aixodia.data.remote.BackupRun
import com.tulipskun.aixodia.data.remote.BackupRunReport
import com.tulipskun.aixodia.data.remote.BackupStatus
import com.tulipskun.aixodia.data.remote.FolderBytes
import com.tulipskun.aixodia.ui.settings.AutoBackupCard
import com.tulipskun.aixodia.ui.settings.BucketCard
import com.tulipskun.aixodia.ui.settings.LastBackupStrip
import com.tulipskun.aixodia.ui.settings.PreviewCard
import com.tulipskun.aixodia.ui.settings.RestoreCard
import com.tulipskun.aixodia.ui.settings.RunCard
import com.tulipskun.aixodia.ui.settings.RunResultCard
import com.tulipskun.aixodia.ui.settings.ScopeCard
import com.tulipskun.aixodia.ui.theme.AIxodiaTheme
import org.junit.Rule
import org.junit.Test

/**
 * The backup page's own parts, rendered rather than assumed.
 *
 * A card that compiles is not a card that can be read: the first pass of this
 * screen shipped folder labels in a colour that sank into their own row, and only
 * a picture could show that. Each case here is a state the operator actually lands
 * in, including the two the engine decides — an old engine with no scope to report,
 * and no automatic backup to configure.
 */
class BackupScreenSnapshots {
    @get:Rule
    val paparazzi = Paparazzi()

    private val modern = BackupStatus(
        configured = true,
        restorable = true,
        bucket = "aixodia",
        prefix = "snapshots/main",
        root = "/root",
        tokenTail = "vCLt",
        excludes = listOf("node_modules", ".venv", "__pycache__"),
        includes = listOf("AIxodia", "projects/aixodia"),
    )

    // An engine from before the allowlist answers without the field at all, which
    // is not the same as answering with an empty list.
    private val oldEngine = BackupStatus(
        configured = true,
        restorable = true,
        bucket = "aixodia",
        prefix = "snapshots/main",
        root = "/root",
        excludes = listOf("node_modules"),
    )

    @Test
    fun scopeCardPicked() = paparazzi.snapshot {
        AIxodiaTheme {
            ScopeCard(
                status = modern,
                includes = listOf("AIxodia", "projects/aixodia"),
                busy = false,
                onEditScope = {},
                onSave = {},
            )
        }
    }

    @Test
    fun scopeCardNoSelection() = paparazzi.snapshot {
        AIxodiaTheme {
            ScopeCard(
                status = modern.copy(includes = emptyList()),
                includes = emptyList(),
                busy = false,
                onEditScope = {},
                onSave = {},
            )
        }
    }

    @Test
    fun scopeCardOldEngine() = paparazzi.snapshot {
        AIxodiaTheme {
            ScopeCard(
                status = oldEngine,
                includes = emptyList(),
                busy = false,
                onEditScope = {},
                onSave = {},
            )
        }
    }

    @Test
    fun previewCardMeasured() = paparazzi.snapshot {
        AIxodiaTheme {
            PreviewCard(
                preview = BackupPreview(
                    root = "/root",
                    files = 47930,
                    dirs = 6120,
                    symlinks = 14,
                    bytes = 4_435_160_264,
                    skipped = 312,
                    skippedExamples = listOf(
                        ".config/pip/ (unreadable: Permission denied)",
                        "tmp/build (not a regular file)",
                    ),
                    folders = listOf(
                        FolderBytes(".cache", 18400, 2_013_265_920),
                        FolderBytes(".cargo", 12040, 1_400_898_048),
                        FolderBytes("AIxodia", 2400, 524_288_000),
                        FolderBytes("aixodia-engine", 1490, 419_430_400),
                    ),
                ),
                error = "",
                busy = false,
                enabled = true,
                lastRun = BackupRun(
                    operation = "backup",
                    ok = true,
                    startedAt = 1793686000,
                    finishedAt = 1793686223,
                    report = BackupRunReport(files = 328, bytes = 41_943_040),
                ),
                onPreview = {},
            )
        }
    }

    @Test
    fun previewCardBeforeRunning() = paparazzi.snapshot {
        AIxodiaTheme {
            PreviewCard(
                preview = null,
                error = "",
                busy = false,
                enabled = true,
                lastRun = null,
                onPreview = {},
            )
        }
    }

    @Test
    fun previewCardRefused() = paparazzi.snapshot {
        AIxodiaTheme {
            PreviewCard(
                preview = null,
                error = "A backup is running; the counts would be of a tree that is " +
                    "being uploaded while it is measured.",
                busy = false,
                enabled = true,
                lastRun = null,
                onPreview = {},
            )
        }
    }

    @Test
    fun autoBackupCardConfigured() = paparazzi.snapshot {
        AIxodiaTheme {
            AutoBackupCard(
                auto = AutoBackupStatus(
                    enabled = true,
                    idleStreakSecs = 214,
                    cpuCurrent = 6.4,
                    lastAutoBackupAt = 1793686223,
                    idleSecs = 300,
                    cpuThreshold = 10.0,
                    cooldownSecs = 1800,
                    restoreReady = true,
                ),
                busy = false,
                onSave = { _, _, _, _ -> },
            )
        }
    }

    @Test
    fun autoBackupCardOldEngine() = paparazzi.snapshot {
        AIxodiaTheme {
            AutoBackupCard(auto = null, busy = false, onSave = { _, _, _, _ -> })
        }
    }
    /**
     * The page as it actually stacks, in three pictures.
     *
     * One screen of the renderer is one screen of the phone, so the page is cut
     * where the reader would scroll: the run and its outcome, the scope and what
     * it covers, then the automatic backup, the bucket and the way back.
     */
    @Composable
    private fun stack(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }

    private val finished = BackupRun(
        operation = "backup",
        ok = true,
        startedAt = 1793686000,
        finishedAt = 1793686223,
        report = BackupRunReport(
            name = "20261103T082053Z",
            root = "/root",
            files = 47930,
            dirs = 6120,
            bytes = 4_435_160_264,
            uploadedBytes = 1_073_741_824,
            filesReused = 44_602,
            objectsReused = 33,
            durationMs = 223_199,
        ),
    )

    private val idle = modern.copy(auto = AutoBackupStatus(enabled = true), run = finished)

    @Test
    fun lastSuccessfulBackupTimestamp() = paparazzi.snapshot {
        AIxodiaTheme {
            LastBackupStrip(
                BackupRun(
                    operation = "backup",
                    ok = true,
                    startedAt = 1793891284,
                    finishedAt = 1793891295,
                    report = BackupRunReport(files = 124079),
                ),
            )
        }
    }

    @Test
    fun noSuccessfulBackupTimestamp() = paparazzi.snapshot {
        AIxodiaTheme {
            LastBackupStrip(null)
        }
    }

    @Test
    fun pageTopIdle() = paparazzi.snapshot {
        AIxodiaTheme {
            stack {
                RunCard(idle, now = 1793686300L, busy = false, onStart = {}, onRefresh = {})
                RunResultCard(finished)
            }
        }
    }

    @Test
    fun pageTopRunning() = paparazzi.snapshot {
        AIxodiaTheme {
            val running = idle.copy(
                operation = "backup",
                startedAt = 1793697814,
                phase = "uploading",
                message = "packing 48475 file(s): 22317 read, 59 container(s) built",
                done = 22317,
                total = 48475,
                bytesDone = 1_400_000_000,
                bytesTotal = 4_435_160_264,
            )
            stack {
                RunCard(running, now = 1793697889L, busy = true, onStart = {}, onRefresh = {})
                RunResultCard(finished)
            }
        }
    }

    @Test
    fun pageScopeUnmeasured() = paparazzi.snapshot {
        AIxodiaTheme {
            stack {
                PreviewCard(
                    preview = null,
                    error = "",
                    busy = false,
                    enabled = true,
                    lastRun = finished,
                    onPreview = {},
                )
                ScopeCard(
                    status = idle,
                    includes = idle.includes.orEmpty(),
                    busy = false,
                        onEditScope = {},
                    onSave = {},
                )
            }
        }
    }

    @Test
    fun pageScopeMeasured() = paparazzi.snapshot {
        AIxodiaTheme {
            stack {
                PreviewCard(
                    preview = BackupPreview(
                        root = "/root",
                        files = 47930,
                        dirs = 6120,
                        bytes = 4_435_160_264,
                        skipped = 312,
                        skippedExamples = listOf(".config/pip/ (unreadable: Permission denied)"),
                        folders = listOf(
                            FolderBytes(".cache", 18400, 2_013_265_920),
                            FolderBytes(".cargo", 12040, 1_400_898_048),
                            FolderBytes("AIxodia", 2400, 524_288_000),
                        ),
                    ),
                    error = "",
                    busy = false,
                    enabled = true,
                    lastRun = finished,
                    onPreview = {},
                )
                ScopeCard(
                    status = idle,
                    includes = idle.includes.orEmpty(),
                    busy = false,
                        onEditScope = {},
                    onSave = {},
                )
            }
        }
    }

    /** The last two cards: the viewport cannot hold three. */
    @Test
    fun pageWhereItGoesAndRestore() = paparazzi.snapshot {
        AIxodiaTheme {
            stack {
                BucketCard(
                    status = idle,
                    token = "",
                    onToken = {},
                    bucket = idle.bucket,
                    onBucket = {},
                    prefix = idle.prefix,
                    onPrefix = {},
                    showToken = false,
                    onToggleToken = {},
                    busy = false,
                    onSave = {},
                )
                RestoreCard(status = idle, busy = false, onRestore = {})
            }
        }
    }
}
