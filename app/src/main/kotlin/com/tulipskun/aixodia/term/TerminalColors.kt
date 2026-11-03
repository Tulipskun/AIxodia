package com.tulipskun.aixodia.term

/**
 * The terminal's palette and defaults. A cell stores a resolved ARGB colour, so
 * the palette is only consulted while parsing an SGR sequence; the renderer
 * never does a lookup per cell.
 */
object TerminalColors {
    const val DEFAULT_FG = 0xFFD7E0EA.toInt()
    const val DEFAULT_BG = 0xFF0B0F14.toInt()
    const val DEFAULT_CURSOR = 0xFFD7E0EA.toInt()

    /** The 256 colours: 16 ANSI, a 6x6x6 cube, then 24 greys. */
    val PALETTE = IntArray(256).also { p ->
        val ansi = intArrayOf(
            0x000000, 0xCD0000, 0x00CD00, 0xCDCD00, 0x0000EE, 0xCD00CD, 0x00CDCD, 0xE5E5E5,
            0x7F7F7F, 0xFF0000, 0x00FF00, 0xFFFF00, 0x5C5CFF, 0xFF00FF, 0x00FFFF, 0xFFFFFF,
        )
        for (i in 0 until 16) p[i] = 0xFF000000.toInt() or ansi[i]
        val steps = intArrayOf(0, 95, 135, 175, 215, 255)
        var i = 16
        for (r in 0 until 6) for (g in 0 until 6) for (b in 0 until 6) {
            p[i++] = 0xFF000000.toInt() or (steps[r] shl 16) or (steps[g] shl 8) or steps[b]
        }
        for (n in 0 until 24) {
            val v = 8 + n * 10
            p[i++] = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }
    }

    fun indexed(i: Int): Int = if (i in 0..255) PALETTE[i] else DEFAULT_FG

    fun rgb(r: Int, g: Int, b: Int): Int =
        0xFF000000.toInt() or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
}

/** Cell attribute bits, one per SGR flag that changes how a glyph is drawn. */
object TerminalAttr {
    const val BOLD = 1
    const val DIM = 2
    const val ITALIC = 4
    const val UNDERLINE = 8
    const val BLINK = 16
    const val INVERSE = 32
    const val INVISIBLE = 64
    const val STRIKE = 128
}

/**
 * What the emulator tells its owner. The parser cannot answer a query itself —
 * only the process on the other end of the socket can — so replies come back
 * through [write].
 */
interface TerminalOutput {
    /** Bytes the emulator owes the shell: a DSR reply, a DA reply, and so on. */
    fun write(bytes: ByteArray)

    /** A screenful changed; the view should redraw. */
    fun onScreenChanged()

    /** OSC 0/2 set the window title. */
    fun onTitleChanged(title: String)

    /** OSC 52 asked to put text on the system clipboard. */
    fun onClipboard(text: String) {}
}
