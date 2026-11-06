package com.tulipskun.aixodia.ui.settings

import androidx.compose.ui.state.ToggleableState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The picker's rule semantics, tested against the shapes a saved scope actually
 * takes: relative rules, an exclusion inside a selected folder, and the old-root
 * prefixed form a root change used to leave behind.
 */
class BackupScopeSelectionTest {

    private fun state(path: String, rules: List<String>) = backupPathState(path, rules)

    @Test
    fun `a folder with no rules is unchecked`() {
        assertEquals(ToggleableState.Off, state("AIxodia", emptyList()))
    }

    @Test
    fun `the root itself is never selectable`() {
        // An empty relative path is `/`; no rule can name it, so it must not
        // read as covered by the whole-tree rules below it.
        assertEquals(ToggleableState.Off, state("", listOf("AIxodia")))
    }

    @Test
    fun `a selected folder reads as checked`() {
        assertEquals(ToggleableState.On, state("AIxodia", listOf("AIxodia")))
        assertEquals(ToggleableState.On, state("projects/aixodia", listOf("projects/aixodia")))
    }

    @Test
    fun `a child of a selected folder reads as checked too`() {
        assertEquals(ToggleableState.On, state("AIxodia/app", listOf("AIxodia")))
    }

    @Test
    fun `an excluded child makes its parent partial`() {
        val rules = listOf("AIxodia", "!AIxodia/build")
        assertEquals(ToggleableState.Indeterminate, state("AIxodia", rules))
        assertEquals(ToggleableState.Off, state("AIxodia/build", rules))
        assertEquals(ToggleableState.On, state("AIxodia/app", rules))
    }

    @Test
    fun `a deeper selection shows on its ancestor`() {
        // The bug this guards: rules were matched only against the current
        // folder, so a pick made elsewhere read as unchecked everywhere else.
        assertEquals(ToggleableState.Indeterminate, state("projects", listOf("projects/aixodia")))
    }

    @Test
    fun `old-root-prefixed rules still match after normalizing`() {
        // load() unwraps these before the picker sees them; the helper trims as
        // a backstop so a stray absolute rule cannot look unchecked.
        assertEquals(
            ToggleableState.On,
            state("root/AIxodia", listOf("root/AIxodia")),
        )
    }

    @Test
    fun `selecting a folder replaces every rule inside it`() {
        val next = updateBackupPathSelection(
            "AIxodia",
            select = true,
            rules = listOf("AIxodia/app", "!AIxodia/build", "projects"),
        )
        assertEquals(listOf("AIxodia", "projects"), next)
    }

    @Test
    fun `unselecting an inherited child writes an exclusion, not a deletion`() {
        val next = updateBackupPathSelection(
            "AIxodia/build",
            select = false,
            rules = listOf("AIxodia"),
        )
        assertEquals(listOf("!AIxodia/build", "AIxodia"), next)
    }

    @Test
    fun `unselecting a top-level folder drops its rules entirely`() {
        val next = updateBackupPathSelection(
            "AIxodia",
            select = false,
            rules = listOf("AIxodia", "!AIxodia/build"),
        )
        assertEquals(emptyList<String>(), next)
    }
}
