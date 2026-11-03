package com.tulipskun.aixodia.term

/**
 * The one-shot Ctrl/Alt/Shift state, as the wire sees it. [xterm] is the
 * parameter xterm puts on a modified key: 1 plus Shift(1), Alt(2), Ctrl(4), so
 * Ctrl+Left is `CSI 1;5D` and Shift+Alt+F5 is `CSI 15;4~`.
 */
data class KeyMods(
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
) {
    val any: Boolean get() = ctrl || alt || shift
    val xterm: Int get() = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)

    companion object {
        val NONE = KeyMods()
    }
}

/**
 * How a key press becomes bytes, the rules every terminal shares: Ctrl turns a
 * letter into its C0 code, Alt is an ESC prefix, Shift prints the shifted
 * character, and a modified cursor/page key moves its modifier into an xterm
 * parameter. The bar, the IME and the hardware keyboard all answer to these so
 * a chord means one thing no matter which of them produced it.
 */
object TerminalKeys {
    /** The C0 code a Ctrl chord sends, or `cp` itself when Ctrl means nothing to it. */
    fun controlFor(cp: Int): Int = when {
        cp in 'a'.code..'z'.code -> cp - 'a'.code + 1
        cp in 'A'.code..'Z'.code -> cp - 'A'.code + 1
        cp == ' '.code || cp == '@'.code || cp == '`'.code || cp == '2'.code -> 0x00
        cp == '['.code || cp == '3'.code -> 0x1B
        cp == '\\'.code || cp == '4'.code -> 0x1C
        cp == ']'.code || cp == '5'.code -> 0x1D
        cp == '^'.code || cp == '6'.code -> 0x1E
        cp == '_'.code || cp == '7'.code || cp == '/'.code -> 0x1F
        cp == '8'.code || cp == '?'.code -> 0x7F
        else -> cp
    }

    private val SHIFTED = mapOf(
        '`' to '~', '1' to '!', '2' to '@', '3' to '#', '4' to '$', '5' to '%',
        '6' to '^', '7' to '&', '8' to '*', '9' to '(', '0' to ')', '-' to '_',
        '=' to '+', '[' to '{', ']' to '}', '\\' to '|', ';' to ':', '\'' to '"',
        ',' to '<', '.' to '>', '/' to '?',
    )

    /** The character a US layout prints for `cp` with Shift down. */
    fun shiftFor(cp: Int): Int = when {
        cp in 'a'.code..'z'.code -> cp - 32
        cp in 'A'.code..'Z'.code -> cp
        else -> SHIFTED.entries.firstOrNull { it.key.code == cp }?.value?.code ?: cp
    }

    /**
     * Text from the keyboard with the latched modifiers applied. Only the first
     * character is modified — a chord is a keystroke — and the rest rides along
     * exactly as the IME produced it.
     */
    fun typed(text: String, mods: KeyMods): String {
        if (text.isEmpty() || !mods.any) return text
        val cp = text.codePointAt(0)
        val rest = text.substring(Character.charCount(cp))
        val sb = StringBuilder()
        if (mods.alt) sb.append('\u001b')
        var out = if (mods.shift) shiftFor(cp) else cp
        if (mods.ctrl) out = controlFor(out)
        sb.appendCodePoint(out)
        sb.append(rest)
        return sb.toString()
    }

    /** An arrow: SS3 in application mode, and a `CSI 1;mod` once a modifier is held. */
    fun cursor(final: Char, mods: KeyMods, application: Boolean): String {
        if (mods.xterm > 1) return "\u001b[1;${mods.xterm}$final"
        val prefix = if (application) "\u001bO" else "\u001b["
        return "$prefix$final"
    }

    /** A numbered key (Ins, Del, PgUp, PgDn, F5–F12): `CSI code;mod~` when modified. */
    fun tilde(code: Int, mods: KeyMods): String =
        if (mods.xterm > 1) "\u001b[$code;${mods.xterm}~" else "\u001b[$code~"

    fun home(mods: KeyMods): String =
        if (mods.xterm > 1) "\u001b[1;${mods.xterm}H" else "\u001b[H"

    fun end(mods: KeyMods): String =
        if (mods.xterm > 1) "\u001b[1;${mods.xterm}F" else "\u001b[F"

    /** Enter is CR; Alt makes it meta-CR, the way xterm sends it. */
    fun enter(mods: KeyMods): String = if (mods.alt) "\u001b\r" else "\r"

    /**
     * Backspace is DEL. With Ctrl it is the readline word-erase (^W), and with
     * Alt the meta form readline reads as the same thing on a hardware keyboard.
     */
    fun backspace(mods: KeyMods): String = when {
        mods.ctrl -> "\u0017"
        mods.alt -> "\u001b\u007f"
        else -> "\u007f"
    }

    /** Tab, backtab when Shift is held, meta-Tab when Alt is. */
    fun tab(mods: KeyMods): String = when {
        mods.shift -> "\u001b[Z"
        mods.alt -> "\u001b\t"
        else -> "\t"
    }

    /** Esc, doubled when Alt is held (meta-Esc). */
    fun escape(mods: KeyMods): String = if (mods.alt) "\u001b\u001b" else "\u001b"

    /**
     * The one character an edit inserted, or `null` when the change was more
     * than a single insertion (a paste, a deletion, a swipe suggestion). The
     * composer only turns a chord into bytes for a single keystroke.
     */
    fun insertedChar(old: String, next: String): Char? {
        if (next.length != old.length + 1) return null
        var p = 0
        while (p < old.length && old[p] == next[p]) p++
        if (next.substring(p + 1) != old.substring(p)) return null
        return next[p]
    }
}

/**
 * What an in-progress composition costs in bytes.
 *
 * A Thai keyboard does not hand over a word at a time. It sends the syllable as
 * it is built and rewrites the end of it as each vowel and tone mark arrives, so
 * a reader who types ไก่ sees ไ, ไก, ไก่, ไก้... The terminal keeps no composition
 * of its own — the text lives in the shell's line — so the only way to answer an
 * update is to do what a thumb does on a backspace key: delete the part that
 * changed and type the new part.
 *
 * Moving the cursor instead would not do it. A line editor reads a cursor-back
 * as a move *within its own buffer*, so the next character is inserted rather
 * than written over and the syllable doubles. A delete is the one primitive that
 * means the same thing to a shell, to readline and to a raw-mode program.
 */
object TerminalCompose {

    /** The edit that turns [previous] into [next]: this many backspaces, then this text. */
    data class Edit(val erases: Int, val types: String) {
        val idle: Boolean get() = erases == 0 && types.isEmpty()
    }

    /**
     * The part the two versions disagree on. Whatever they share at the front is
     * left on the line untouched, so an update that only appended costs no
     * backspaces at all.
     *
     * Counting is by code point because that is what a line editor deletes: a
     * Thai tone mark rides on the letter before it, draws in the same cell, and
     * is still a character of its own to the shell.
     */
    fun edit(previous: String, next: String): Edit {
        val before = codePoints(previous)
        val after = codePoints(next)
        var shared = 0
        while (shared < before.size && shared < after.size && before[shared] == after[shared]) {
            shared++
        }
        val tail = after.copyOfRange(shared, after.size)
        return Edit(erases = before.size - shared, types = String(tail, 0, tail.size))
    }

    private fun codePoints(text: String): IntArray {
        val out = IntArray(text.codePointCount(0, text.length))
        var index = 0
        var count = 0
        while (index < text.length) {
            val cp = text.codePointAt(index)
            out[count++] = cp
            index += Character.charCount(cp)
        }
        return out
    }
}
