package com.tulipskun.aixodia.data.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backup page is drawn entirely out of what `GET /api/backup` answers, and
 * this host cannot compile the app to find out whether the read is right — so
 * the parser is tested where the counts actually come from.
 */
class BackupStatusParseTest {

    @Test
    fun `a finished backup keeps every count the engine reported`() {
        val s = parseBackupStatus(
            JSONObject(
                """
                {
                  "configured": true, "bucket": "me/b", "prefix": "p", "root": "/root",
                  "excludes": ["target", "*.pyc"],
                  "finished": {
                    "operation": "backup", "started_at": 100, "finished_at": 141,
                    "summary": {"ok": true, "report": {
                      "name": "20261101T053000Z", "files": 34102, "dirs": 900,
                      "symlinks": 3, "bytes": 39845900, "uploaded_bytes": 11800000,
                      "files_reused": 33989, "objects_reused": 12,
                      "skipped": ["/root/.cache/core.1234"],
                      "deleted_manifests": 1,
                      "duration_ms": 41200, "root": "/root", "bucket": "me/b"
                    }}
                  }
                }
                """.trimIndent(),
            ),
        )
        assertTrue(s.configured)
        assertEquals(listOf("target", "*.pyc"), s.excludes)
        assertTrue(s.reportsExcludes)
        val run = s.run!!
        assertTrue(run.ok)
        assertEquals("backup", run.operation)
        assertEquals(141L, run.finishedAt)
        val report = run.report!!
        assertEquals(34102, report.files)
        assertEquals(33989, report.filesReused)
        assertEquals(11800000L, report.uploadedBytes)
        assertEquals(41200L, report.durationMs)
        assertEquals(listOf("/root/.cache/core.1234"), report.skipped)
        assertEquals(1, report.deletedManifests)
        // The one-line form the chat notice used is still built from the same slot.
        assertTrue(s.lastRun.contains("34102 files"))
    }

    @Test
    fun `an engine that does not name its scope is unknown, not empty`() {
        val silent = parseBackupStatus(JSONObject("""{"configured": true}"""))
        assertNull(silent.excludes)
        assertFalse(silent.reportsExcludes)
        assertNull(silent.run)
        assertFalse(silent.running)

        // An empty list is an answer, and a different one: it means the walker
        // leaves nothing out, which the page must be able to show honestly.
        val cleared = parseBackupStatus(JSONObject("""{"excludes": []}"""))
        assertTrue(cleared.reportsExcludes)
        assertTrue(cleared.excludes!!.isEmpty())
    }

    @Test
    fun `a run in flight carries the totals the progress bar is drawn from`() {
        val s = parseBackupStatus(
            JSONObject(
                """
                {"active": {"operation": "backup", "started_at": 900, "progress": {
                  "phase": "uploading", "done": 12000, "total": 34102,
                  "bytes_done": 4100000, "bytes_total": 39845900, "message": "committing"
                }}}
                """.trimIndent(),
            ),
        )
        assertTrue(s.running)
        assertEquals("backup", s.operation)
        assertEquals("uploading", s.phase)
        assertEquals("committing", s.message)
        assertEquals(900L, s.startedAt)
        assertEquals(12000, s.done)
        assertEquals(34102, s.total)
        assertEquals(39845900L, s.bytesTotal)
    }

    /**
     * A scan reports no totals at all until it ends, so a page that insisted on
     * counts would show an empty bar for the longest phase of a first backup.
     */
    @Test
    fun `a phase with nothing counted yet reads as unmeasured rather than as zero`() {
        val s = parseBackupStatus(
            JSONObject("""{"active": {"operation": "backup", "progress": {"phase": "scanning"}}}"""),
        )
        assertTrue(s.running)
        assertEquals(0, s.total)
        assertEquals(0L, s.bytesTotal)
    }

    @Test
    fun `a failed run says so in the engine's own words`() {
        val s = parseBackupStatus(
            JSONObject(
                """
                {"finished": {"operation": "restore", "summary": {
                  "ok": false, "error": "the bucket held no manifest"}}}
                """.trimIndent(),
            ),
        )
        val run = s.run!!
        assertFalse(run.ok)
        assertNull(run.report)
        assertEquals("the bucket held no manifest", run.error)
        assertTrue(s.lastRun.startsWith("restore failed"))
    }

    /**
     * Restore reports differ from a backup's: it counts what was already right
     * on disk and has no upload figure at all, so the fields a page must not
     * invent are the ones worth pinning down.
     */
    @Test
    fun `a restore report answers with its own counts`() {
        val named = parseBackupStatus(
            JSONObject(
                """
                {"finished": {"operation": "restore", "summary": {"ok": true, "report": {
                  "name": "20261031T000000Z", "files": 5, "unchanged": 2,
                  "bytes": 1024, "duration_ms": 800}}}}
                """.trimIndent(),
            ),
        )
        val report = named.run!!.report!!
        assertEquals("20261031T000000Z", report.name)
        assertEquals(2, report.unchanged)
        assertEquals(800L, report.durationMs)
        // Never read by a restore, and absent from the answer: it stays zero
        // rather than becoming a number the engine did not give.
        assertEquals(0L, report.uploadedBytes)
        assertEquals(0, report.filesReused)
    }

    /** An engine older than the split answers only `configured`. */
    @Test
    fun `a tree-less engine still offers a restore`() {
        val old = parseBackupStatus(JSONObject("""{"configured": false}"""))
        assertFalse(old.restorable)
        val split = parseBackupStatus(
            JSONObject("""{"configured": false, "restorable": true, "needs": ["root"]}"""),
        )
        assertTrue(split.restorable)
        assertFalse(split.configured)
        assertEquals(listOf("root"), split.needs)
    }

    /**
     * The allowlist is the picker's answer, and it is read the way the exclusions
     * are: absent means an engine too old to have one, empty means the operator
     * chose everything. Telling those apart is what decides whether the page shows
     * a folder picker or the raw patterns.
     */
    @Test
    fun `an engine without an allowlist is unknown, not everything`() {
        val old = parseBackupStatus(JSONObject("""{"excludes": ["target"]}"""))
        assertNull(old.includes)
        assertFalse(old.reportsScope)
        assertNull(old.auto)

        val widened = parseBackupStatus(JSONObject("""{"includes": []}"""))
        assertTrue(widened.reportsScope)
        assertTrue(widened.includes!!.isEmpty())

        val picked = parseBackupStatus(
            JSONObject("""{"includes": [".codex", "projects/aixodia/app"]}"""),
        )
        assertEquals(listOf(".codex", "projects/aixodia/app"), picked.includes)
    }

    @Test
    fun `the automatic backup answers with its thresholds and what it can see`() {
        val auto = parseBackupStatus(
            JSONObject(
                """
                {"auto": {"enabled": true, "idle_secs": 600, "cpu_threshold": 10.0,
                          "cooldown_secs": 1800, "idle_streak_secs": 45,
                          "cpu_current": 3.5, "last_auto_backup_at": 1730400000,
                          "restore_ready": true, "cpu_history": [12.5, 8.0, 3.5]}}
                """.trimIndent(),
            ),
        ).auto!!
        assertTrue(auto.enabled)
        assertEquals(600L, auto.idleSecs)
        assertEquals(10.0, auto.cpuThreshold, 0.001)
        assertEquals(1800L, auto.cooldownSecs)
        assertEquals(45L, auto.idleStreakSecs)
        assertEquals(3.5, auto.cpuCurrent, 0.001)
        assertEquals(1730400000L, auto.lastAutoBackupAt)
        assertTrue(auto.restoreReady)
        assertEquals(listOf(12.5, 8.0, 3.5), auto.cpuHistory)

        val blocked = parseBackupStatus(
            JSONObject("""{"auto": {"enabled": true, "restore_ready": false}}"""),
        ).auto!!
        assertFalse(blocked.restoreReady)
        assertTrue(blocked.cpuHistory.isEmpty())

        // An engine with no automatic backup is not an engine with one switched
        // off: the card says so and offers nothing to set.
        assertNull(parseBackupStatus(JSONObject("""{"configured": true}""")).auto)
    }

    /**
     * The engine now writes every file it can and names the ones it could not, so
     * the line that says a restore finished has to carry how short the tree is —
     * "5 files written" on its own reads as a whole machine.
     */
    @Test
    fun `a restore that left files out says how many`() {
        val short = parseBackupStatus(
            JSONObject(
                """
                {"finished": {"operation": "restore", "summary": {"ok": true, "report": {
                  "name": "20261101T000000Z", "files": 5, "unchanged": 1,
                  "bytes": 2048, "duration_ms": 1500,
                  "failed": ["gone.txt: backup: download on gone.txt failed: http 404"]}}}}
                """.trimIndent(),
            ),
        )
        val report = short.run!!.report!!
        assertEquals(1, report.failed.size)
        assertTrue(
            "the count belongs on the summary line: ${short.lastRun}",
            short.lastRun.endsWith("1 could not be written"),
        )

        val whole = parseBackupStatus(
            JSONObject(
                """
                {"finished": {"operation": "restore", "summary": {"ok": true, "report": {
                  "name": "20261101T000000Z", "files": 5, "unchanged": 1,
                  "bytes": 2048, "duration_ms": 1500}}}}
                """.trimIndent(),
            ),
        )
        assertTrue(whole.run!!.report!!.failed.isEmpty())
        assertFalse(whole.lastRun.contains("could not be written"))
    }

    @Test
    fun `last successful backup survives a newer restore result`() {
        val status = parseBackupStatus(
            JSONObject(
                """
                {
                  "finished": {"operation": "restore", "started_at": 200, "finished_at": 210,
                    "summary": {"ok": true, "report": {"files": 10, "duration_ms": 900}}},
                  "last_backup": {"operation": "backup", "started_at": 100, "finished_at": 150,
                    "summary": {"ok": true, "report": {"files": 12, "bytes": 2048, "uploaded_bytes": 512, "duration_ms": 1200}}}
                }
                """.trimIndent(),
            ),
        )
        assertEquals("restore", status.run!!.operation)
        assertEquals("backup", status.lastBackup!!.operation)
        assertEquals(150L, status.lastBackup!!.finishedAt)
        assertEquals(12, status.lastBackup!!.report!!.files)
    }
}
