package com.tulipskun.aixodia.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalKeysTest {

    private val ctrl = KeyMods(ctrl = true)
    private val alt = KeyMods(alt = true)
    private val shift = KeyMods(shift = true)

    @Test
    fun ctrl_turns_a_letter_into_its_c0_code() {
        assertEquals(0x03, TerminalKeys.controlFor('c'.code))
        assertEquals(0x03, TerminalKeys.controlFor('C'.code))
        assertEquals(0x01, TerminalKeys.controlFor('a'.code))
        assertEquals(0x1a, TerminalKeys.controlFor('z'.code))
    }

    @Test
    fun ctrl_covers_the_punctuation_terminal_users_rely_on() {
        assertEquals(0x00, TerminalKeys.controlFor(' '.code))
        assertEquals(0x00, TerminalKeys.controlFor('@'.code))
        assertEquals(0x1b, TerminalKeys.controlFor('['.code))
        assertEquals(0x1c, TerminalKeys.controlFor('\\'.code))
        assertEquals(0x1d, TerminalKeys.controlFor(']'.code))
        assertEquals(0x1e, TerminalKeys.controlFor('^'.code))
        assertEquals(0x1f, TerminalKeys.controlFor('_'.code))
        assertEquals(0x1f, TerminalKeys.controlFor('/'.code))
        assertEquals(0x7f, TerminalKeys.controlFor('?'.code))
    }

    @Test
    fun ctrl_leaves_a_key_it_means_nothing_to_alone() {
        assertEquals('é'.code, TerminalKeys.controlFor('é'.code))
    }

    @Test
    fun shift_prints_the_shifted_character() {
        assertEquals('C'.code, TerminalKeys.shiftFor('c'.code))
        assertEquals('C'.code, TerminalKeys.shiftFor('C'.code))
        assertEquals('!'.code, TerminalKeys.shiftFor('1'.code))
        assertEquals('?'.code, TerminalKeys.shiftFor('/'.code))
        assertEquals('_'.code, TerminalKeys.shiftFor('-'.code))
        assertEquals('~'.code, TerminalKeys.shiftFor('`'.code))
    }

    @Test
    fun typed_is_untouched_when_no_modifier_is_latched() {
        assertEquals("abc", TerminalKeys.typed("abc", KeyMods.NONE))
    }

    @Test
    fun typed_applies_ctrl_then_alt() {
        assertEquals("\u0003", TerminalKeys.typed("c", ctrl))
        assertEquals("\u001bc", TerminalKeys.typed("c", alt))
        assertEquals("\u001b\u0003", TerminalKeys.typed("c", KeyMods(ctrl = true, alt = true)))
    }

    @Test
    fun ctrl_shift_sends_the_ts_letter() {
        // Ctrl+Shift+T is the same 0x14 as Ctrl+T, only the case of the
        // character the IME produced differs.
        assertEquals("\u0014", TerminalKeys.typed("t", KeyMods(ctrl = true, shift = true)))
        assertEquals("\u0014", TerminalKeys.typed("T", KeyMods(ctrl = true, shift = true)))
    }

    @Test
    fun only_the_first_character_of_a_commit_is_modified() {
        assertEquals("\u0003d", TerminalKeys.typed("cd", ctrl))
    }

    @Test
    fun cursor_keys_take_the_xterm_modifier_parameter() {
        assertEquals("\u001b[A", TerminalKeys.cursor('A', KeyMods.NONE, application = false))
        assertEquals("\u001bOA", TerminalKeys.cursor('A', KeyMods.NONE, application = true))
        assertEquals("\u001b[1;5D", TerminalKeys.cursor('D', ctrl, application = false))
        assertEquals("\u001b[1;2A", TerminalKeys.cursor('A', shift, application = false))
        // A modified key is always CSI, even in application cursor mode.
        assertEquals("\u001b[1;5C", TerminalKeys.cursor('C', ctrl, application = true))
        assertEquals("\u001b[1;8C", TerminalKeys.cursor('C', KeyMods(ctrl = true, alt = true, shift = true), application = false))
    }

    @Test
    fun numbered_keys_take_the_xterm_modifier_parameter() {
        assertEquals("\u001b[3~", TerminalKeys.tilde(3, KeyMods.NONE))
        assertEquals("\u001b[3;5~", TerminalKeys.tilde(3, ctrl))
        assertEquals("\u001b[5;5~", TerminalKeys.tilde(5, ctrl))
        assertEquals("\u001b[6;3~", TerminalKeys.tilde(6, alt))
    }

    @Test
    fun home_and_end_carry_the_modifier() {
        assertEquals("\u001b[H", TerminalKeys.home(KeyMods.NONE))
        assertEquals("\u001b[1;5H", TerminalKeys.home(ctrl))
        assertEquals("\u001b[F", TerminalKeys.end(KeyMods.NONE))
        assertEquals("\u001b[1;5F", TerminalKeys.end(ctrl))
    }

    @Test
    fun enter_backspace_tab_and_escape_follow_the_modifier() {
        assertEquals("\r", TerminalKeys.enter(KeyMods.NONE))
        assertEquals("\u001b\r", TerminalKeys.enter(alt))

        assertEquals("\u007f", TerminalKeys.backspace(KeyMods.NONE))
        assertEquals("\u0017", TerminalKeys.backspace(ctrl))
        assertEquals("\u001b\u007f", TerminalKeys.backspace(alt))

        assertEquals("\t", TerminalKeys.tab(KeyMods.NONE))
        assertEquals("\u001b[Z", TerminalKeys.tab(shift))
        assertEquals("\u001b\t", TerminalKeys.tab(alt))

        assertEquals("\u001b", TerminalKeys.escape(KeyMods.NONE))
        assertEquals("\u001b\u001b", TerminalKeys.escape(alt))
    }

    @Test
    fun inserted_char_finds_a_single_keystroke_and_rejects_everything_else() {
        assertEquals('c', TerminalKeys.insertedChar("ab", "abc"))
        assertEquals('c', TerminalKeys.insertedChar("ab", "acb"))
        assertEquals('c', TerminalKeys.insertedChar("", "c"))
        assertNull(TerminalKeys.insertedChar("ab", "ab"))
        assertNull(TerminalKeys.insertedChar("abc", "ab"))
        assertNull(TerminalKeys.insertedChar("ab", "acd"))
        assertNull(TerminalKeys.insertedChar("", "paste"))
    }
}
