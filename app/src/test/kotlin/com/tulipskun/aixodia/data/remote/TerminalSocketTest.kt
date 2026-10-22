package com.tulipskun.aixodia.data.remote

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
    fun aLongBackgroundReturnRebuildsAnOpenStream() {
        assertTrue(shouldReattachAfterBackground(TermState.OPEN, wanted = true, awayMs = 60_000))
        // A glance at a notification must not reset the screen.
        assertEquals(false, shouldReattachAfterBackground(TermState.OPEN, wanted = true, awayMs = 2_000))
        // A panel that is closed, ended or still dialing is not reattached here.
        assertEquals(false, shouldReattachAfterBackground(TermState.CLOSED, wanted = false, awayMs = 60_000))
        assertEquals(false, shouldReattachAfterBackground(TermState.ENDED, wanted = true, awayMs = 60_000))
        assertEquals(false, shouldReattachAfterBackground(TermState.CONNECTING, wanted = true, awayMs = 60_000))
    }

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
    fun aSizeMeasuredDuringOpenIsResentOnceTheStreamIsUp() {
        // The view laid out while openTerminal() was in flight: the open frame
        // carried the old/default size, so the latest measurement must follow.
        assertEquals(100 to 40, sizeToResend(100 to 40, sentCols = 0, sentRows = 0))
        assertEquals(90 to 30, sizeToResend(90 to 30, sentCols = 80, sentRows = 24))
        // Nothing new to say when the open already carried this exact size.
        assertNull(sizeToResend(90 to 30, sentCols = 90, sentRows = 30))
        // A view that has not been measured yet must not force a bogus size.
        assertNull(sizeToResend(null, sentCols = 0, sentRows = 0))
        assertNull(sizeToResend(0 to 0, sentCols = 80, sentRows = 24))
    }

    @Test
    fun resizeFrameCarriesBothDimensions() {
        // The engine ignores a resize that names only one dimension, so both
        // keys have to be in this frame — it is the whole point of the frame.
        val json = resizeFrame(cols = 100, rows = 30)
        assertTrue(json, json.contains("\"type\":\"resize\""))
        assertTrue(json, json.contains("\"cols\":100"))
        assertTrue(json, json.contains("\"rows\":30"))
    }
}
