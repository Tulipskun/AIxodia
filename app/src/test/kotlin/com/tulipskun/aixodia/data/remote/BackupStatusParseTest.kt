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
                  "retention": 14, "gc": false, "excludes": ["target", "*.pyc"],
                  "finished": {
                    "operation": "backup", "started_at": 100, "finished_at": 141,
                    "summary": {"ok": true, "report": {
                      "name": "20261101T053000Z", "files": 34102, "dirs": 900,
                      "symlinks": 3, "bytes": 39845900, "uploaded_bytes": 11800000,
                      "files_reused": 33989, "objects_reused": 12,
                      "skipped": ["/root/.cache/core.1234"],
                      "deleted_manifests": 1, "deleted_objects": 7,
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
        assertEquals(7, report.deletedObjects)
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
}
