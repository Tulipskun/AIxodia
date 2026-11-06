package com.tulipskun.aixodia.data.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for parsing the engine's backup file and folder listing.
 */
class BackupScopeParseTest {

    @Test
    fun `a listing keeps the order the engine chose and its way back`() {
        val browse = parseBackupBrowse(
            JSONObject(
                """
                {
                  "path": "/root", "parent": "/",
                  "entries": [
                    {"name": ".codex", "path": "/root/.codex", "is_dir": true},
                    {"name": "notes.txt", "path": "/root/notes.txt", "is_dir": false}
                  ],
                  "truncated": false
                }
                """.trimIndent(),
            ),
        )
        assertEquals("/root", browse.path)
        assertEquals("/", browse.parent)
        assertEquals(2, browse.entries.size)
        assertEquals(".codex", browse.entries[0].name)
        assertTrue(browse.entries[0].isDir)
        assertFalse(browse.entries[1].isDir)
        assertEquals("/root/notes.txt", browse.entries[1].path)
        assertFalse(browse.truncated)
    }

    /** The top of the tree has nowhere above it, and a null is not the string "null". */
    @Test
    fun `a listing at the top of the filesystem has no parent to walk back to`() {
        val browse = parseBackupBrowse(JSONObject("""{"path": "/", "parent": null, "entries": []}"""))
        assertEquals("", browse.parent)
        assertTrue(browse.entries.isEmpty())
    }

    @Test
    fun `a preview names the folders by weight and the paths it could not read`() {
        val preview = parseBackupPreview(
            JSONObject(
                """
                {
                  "root": "/root", "includes": [".codex"],
                  "entries": 40, "files": 34324, "dirs": 5100, "symlinks": 3,
                  "bytes": 4325163, "skipped": 2,
                  "skipped_examples": ["a/core.1234 (unreadable: Permission denied)"],
                  "folders": [
                    {"name": ".codex", "files": 34000, "bytes": 4000000},
                    {"name": "projects", "files": 300, "bytes": 300000}
                  ]
                }
                """.trimIndent(),
            ),
        )
        assertEquals(34324, preview.files)
        assertEquals(5100, preview.dirs)
        assertEquals(3, preview.symlinks)
        assertEquals(4325163L, preview.bytes)
        assertEquals(2, preview.skipped)
        assertEquals(1, preview.skippedExamples.size)
        assertEquals(".codex", preview.folders[0].name)
        assertEquals(4000000L, preview.folders[0].bytes)
        assertEquals(300, preview.folders[1].files)
    }

    /** A scope that covers nothing is a real answer the card has to show as zero. */
    @Test
    fun `an empty scope reads as empty and not as a missing reply`() {
        val preview = parseBackupPreview(JSONObject("""{"root": "/root", "files": 0, "bytes": 0}"""))
        assertEquals(0, preview.files)
        assertEquals(0L, preview.bytes)
        assertTrue(preview.folders.isEmpty())
        assertTrue(preview.skippedExamples.isEmpty())
    }
}
