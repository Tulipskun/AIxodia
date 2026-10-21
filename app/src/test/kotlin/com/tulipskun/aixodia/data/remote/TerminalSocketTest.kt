package com.tulipskun.aixodia.data.remote

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The terminal is spoken to in two directions and neither can be tried on a
 * device here: the address it dials, and the three control frames the engine
 * sends. Both are pure, so both are checked here.
 */
class TerminalSocketTest {

    @Test
    fun terminalUrlKeepsTheTunnelAndSwapsThePath() {
        assertEquals(
            "wss://flat-clip.trycloudflare.com/terminal",
            TerminalSocket.terminalUrlFor("wss://flat-clip.trycloudflare.com/ws"),
        )
        // Discovery hands over an https endpoint before it becomes a socket URL;
        // the terminal has to read it the same way the chat does.
        assertEquals("wss://host/terminal", TerminalSocket.terminalUrlFor("https://host"))
        assertEquals("ws://127.0.0.1:8787/terminal", TerminalSocket.terminalUrlFor("http://127.0.0.1:8787"))
        assertEquals("wss://host/terminal", TerminalSocket.terminalUrlFor("wss://host/ws/"))
    }

    @Test
    fun terminalUrlRefusesWhatIsNotAnAddress() {
        assertEquals("", TerminalSocket.terminalUrlFor(""))
        assertEquals("", TerminalSocket.terminalUrlFor("   "))
        assertEquals("", TerminalSocket.terminalUrlFor("host.trycloudflare.com"))
        assertEquals("", TerminalSocket.terminalUrlFor("ftp://host"))
        assertEquals("", TerminalSocket.terminalUrlFor("wss://"))
    }

    @Test
    fun readyFrameCarriesTheShellAndItsSize() {
        val ev = parseEvent(
            """{"type":"ready","shell":"/bin/bash","cwd":"/kaggle/working","cols":100,"rows":30}"""
        )
        assertEquals(TermEvent.Ready("/bin/bash", "/kaggle/working", 100, 30), ev)
    }

    @Test
    fun readyFrameCarriesTheShellPid() {
        val ev = parseEvent(
            """{"type":"ready","shell":"/bin/bash","cwd":"/root","cols":100,"rows":30,"id":"t-1","pid":4242}"""
        )
        assertEquals(TermEvent.Ready("/bin/bash", "/root", 100, 30, "t-1", 4242), ev)
    }

    @Test
    fun exitFrameCarriesTheCode() {
        assertEquals(TermEvent.Exit(3), parseEvent("""{"type":"exit","code":3}"""))
    }

    @Test
    fun errorFrameCarriesTheReason() {
        assertEquals(
            TermEvent.Failed("shell not found: /bin/zsh"),
            parseEvent("""{"type":"error","error":"shell not found: /bin/zsh"}"""),
        )
    }

    @Test
    fun unknownOrBrokenControlFramesAreIgnored() {
        // Ending a working shell over a frame we do not understand would be the
        // worst possible answer, so these all read as "nothing to do".
        assertNull(parseEvent(""))
        assertNull(parseEvent("not json"))
        assertNull(parseEvent("[]"))
        assertNull(parseEvent("""{"type":"pong"}"""))
    }

    @Test
    fun resizeFrameCarriesBothDimensions() {
        val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            .adapter(ResizeFrame::class.java)
        val json = adapter.toJson(ResizeFrame(type = "resize", cols = 100, rows = 30))
        // The engine ignores a resize that names only one dimension, so both
        // keys have to be in this frame — it is the whole point of the frame.
        assertTrue(json, json.contains("\"type\":\"resize\""))
        assertTrue(json, json.contains("\"cols\":100"))
        assertTrue(json, json.contains("\"rows\":30"))
    }
}
