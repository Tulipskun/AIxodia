package com.tulipskun.aixodia.ui.settings

import com.tulipskun.aixodia.data.remote.BackupBrowse
import com.tulipskun.aixodia.data.remote.BrowseEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic the scope picker rests on: whether a browsed folder is still
 * inside the bound it was told to stay in, what the allowlist entry for it is, and
 * what one tap on a folder's checkbox does to the list. All of it is text on
 * canonical paths, and a wrong answer here is a scope that quietly matches nothing
 * — so the engine is not the only place these get checked.
 */
class BackupScopeTest {

    @Test
    fun `a picked folder becomes a path from the root, not an absolute one`() {
        assertEquals(".codex", relativise("/root", "/root/.codex"))
        assertEquals("projects/aixodia/app", relativise("/root", "/root/projects/aixodia/app"))
        // The engine's own root can be written with or without its slash.
        assertEquals(".codex", relativise("/root/", "/root/.codex"))
        // The root itself is not a folder inside the root: it is everything, which
        // the allowlist says by being empty.
        assertEquals("", relativise("/root", "/root"))
        assertEquals("", relativise("/root/", "/root"))
    }

    @Test
    fun `a folder that only looks like a sibling is not relativised away`() {
        // "/rootbackup" starts with "/root", and a naive prefix strip would turn it
        // into "backup" — an entry that names a folder nobody picked.
        assertEquals("/rootbackup", relativise("/root", "/rootbackup"))
        assertEquals("/rootbackup/notes", relativise("/root", "/rootbackup/notes"))
    }

    @Test
    fun `the bound holds the picker inside the root and nowhere else`() {
        assertTrue(isUnder("/root", "/root"))
        assertTrue(isUnder("/root/projects/app", "/root"))
        assertTrue(isUnder("/rootbackup", "/rootbackup/"))
        assertFalse(isUnder("/rootbackup", "/root"))
        assertFalse(isUnder("/home/user", "/root"))
        // An unbounded browser has no wall to check against.
        assertTrue(isUnder("/anything/at/all", ""))
    }

    /**
     * The guard the scope sheet needs: a pick that is the root, or already inside a
     * folder the operator listed, would be a duplicate that changes nothing.
     */
    @Test
    fun `a pick inside a folder already listed is already covered`() {
        val includes = listOf("projects/aixodia")
        assertTrue(isAlreadyCovered(includes, "projects/aixodia/app"))
        assertTrue(isAlreadyCovered(includes, "projects/aixodia"))
        // Widening a pick back out to its own parent is the same no-op, in the
        // other direction.
        assertTrue(isAlreadyCovered(includes, "projects"))
        assertFalse(isAlreadyCovered(includes, "projects/other"))
        assertFalse(isAlreadyCovered(includes, ".codex"))
        assertFalse(isAlreadyCovered(emptyList(), "anything"))
    }

    /**
     * The tick list the sheet opens with, and the arithmetic behind one tap on it.
     * A checkbox that misreports the scope is worse than no checkbox: the operator
     * reads green where the engine is about to skip a folder.
     */
    @Test
    fun `only directories the walker may enter are offered to tick`() {
        val listing = BackupBrowse(
            path = "/root",
            parent = "/",
            entries = listOf(
                BrowseEntry("notes", "/root/notes", isDir = true, isSymlink = false, canEnter = true),
                BrowseEntry("README", "/root/README", isDir = false, isSymlink = false, canEnter = true),
                // A link and a mount both tick green and back up nothing, so neither
                // is a choice the sheet should offer.
                BrowseEntry(".ssh", "/root/.ssh", isDir = true, isSymlink = true, canEnter = false),
                BrowseEntry("mnt", "/root/mnt", isDir = true, isSymlink = false, canEnter = false),
                BrowseEntry("cache", "/root/cache", isDir = true, isSymlink = false, canEnter = true),
            ),
        )
        assertEquals(listOf("cache", "notes"), rootFolderNames(listing))
        assertEquals(emptyList<String>(), rootFolderNames(null))
    }

    @Test
    fun `a folder holding a narrower pick is not shown as untouched`() {
        assertEquals(FolderPick.Off, pickOf(emptyList(), "projects"))
        assertEquals(FolderPick.Whole, pickOf(listOf("projects"), "projects"))
        // The subtree is in the backup, so the row is ticked — Partial rather than
        // Whole is what keeps the caption honest about how narrow it really is.
        assertEquals(FolderPick.Partial, pickOf(listOf("projects/aixodia/app"), "projects"))
        // A sibling that merely shares the prefix is not inside it.
        assertEquals(FolderPick.Off, pickOf(listOf("projects-old"), "projects"))
        assertEquals(FolderPick.Off, pickOf(listOf("projects/aixodia"), "projects/aixodia-app"))
    }

    @Test
    fun `one tap on a folder adds it, and another tap drops what was inside it`() {
        assertEquals(listOf("notes"), toggleFolder(emptyList(), "notes"))

        // Turning a partial folder on is the operator asking for more: the narrow
        // picks collapse into the whole folder rather than lingering beside it.
        val narrow = listOf("projects/aixodia/app", "notes")
        assertEquals(listOf("notes", "projects"), toggleFolder(narrow, "projects"))

        // Turning anything off drops it and whatever was listed under it — those
        // paths only ever meant "this part of the tree" while the part was ticked.
        assertEquals(listOf("notes"), toggleFolder(narrow + "projects", "projects"))
        assertEquals(listOf("projects", "projects/aixodia/app"), toggleFolder(narrow + "projects", "notes"))

        // Repeating a tap on a whole folder unticks it rather than doubling it.
        assertEquals(emptyList<String>(), toggleFolder(listOf("notes"), "notes"))
    }

    @Test
    fun `one tap adds or removes a folder exclusion without changing other rules`() {
        assertEquals(listOf(".cache/"), toggleExcludedFolder(emptyList(), ".cache"))
        assertEquals(
            listOf(".cache/", "target/"),
            toggleExcludedFolder(listOf(".cache/"), "target"),
        )
        assertEquals(
            listOf("target/"),
            toggleExcludedFolder(listOf(".cache/", "target/"), ".cache"),
        )
        assertEquals(
            listOf("target/"),
            toggleExcludedFolder(listOf("target/", "target/"), "target"),
        )
        assertEquals("projects/cache/", excludedFolderPath("projects/cache"))
    }

    @Test
    fun `a pick the engine no longer lists stays removable`() {
        // The folder was renamed or deleted on the engine after it was chosen; the
        // scope still names it, so the sheet must show it and let it go.
        val includes = listOf("gone", "live/keep", "notes")
        assertEquals(listOf("gone"), stalePicks(includes, listOf("live", "notes")))
        // A pick nested inside a listed folder is not stale for the listing not
        // naming the child: only the first component has to exist.
        assertEquals(emptyList<String>(), stalePicks(includes, listOf("gone", "live", "notes")))
        // Removing it by name also removes a path picked deeper inside it, and does
        // not put the name back the way a tap on the row would.
        assertEquals(
            listOf("live/keep", "notes"),
            dropPick(listOf("gone/old", "live/keep", "notes"), "gone"),
        )
        assertEquals(listOf("notes"), dropPick(listOf("gone", "gone/old", "notes"), "gone"))
    }
}
