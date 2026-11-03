package com.tulipskun.aixodia.term

import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/**
 * Renders [TerminalView] on the JVM with Paparazzi, so the grid, the colours
 * and the glyph metrics can be checked in seconds instead of on a device.
 */
class TerminalViewSnapshots {

    @get:Rule
    val paparazzi = Paparazzi()

    private class Sink : TerminalOutput {
        override fun write(bytes: ByteArray) = Unit
        override fun onScreenChanged() = Unit
        override fun onTitleChanged(title: String) = Unit
    }

    private fun render(script: TerminalEmulator.() -> Unit) {
        val emulator = TerminalEmulator(80, 24, Sink())
        emulator.script()
        val view = TerminalView(paparazzi.context)
        view.setEmulator(emulator)
        paparazzi.snapshot(view)
    }

    @Test
    fun shellWithColours() = render {
        write("\u001b[1;32muser@host\u001b[0m:\u001b[1;34m~/proj\u001b[0m$ ls --color=always\r\n".toByteArray())
        write("\u001b[1;34msrc\u001b[0m  \u001b[1;34mdocs\u001b[0m  README.md  \u001b[1;32mbuild.sh\u001b[0m\r\n".toByteArray())
        write("user@host:~/proj$ \u001b[7m \u001b[0m".toByteArray())
    }

    @Test
    fun thaiAndWideGlyphs() = render {
        write("สวัสดีครับ ทดสอบภาษาไทย\r\n".toByteArray())
        write("wide: \u4e2d\u6587\u5b57 ok\r\n".toByteArray())
        write("bold \u001b[1mตัวหนา\u001b[0m  underline \u001b[4mเส้นใต้\u001b[0m\r\n".toByteArray())
        write("\u001b[41m red bg \u001b[0m \u001b[38;2;255;180;0mtruecolour\u001b[0m\r\n".toByteArray())
    }

    @Test
    fun fullScreenBoxDrawing() = render {
        write("\u001b[2J\u001b[H".toByteArray())
        write("\u250c\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2510\r\n".toByteArray())
        write("\u2502  opencode   \u2502\r\n".toByteArray())
        write("\u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2518\r\n".toByteArray())
    }
}
