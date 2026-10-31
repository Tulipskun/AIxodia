package com.tulipskun.aixodia.data.remote

import android.content.Context
import android.content.SharedPreferences
import com.tulipskun.aixodia.ConnConfig
import com.tulipskun.aixodia.SettingsStore
import com.tulipskun.aixodia.data.model.AiOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * A live turn must be asked for in slices, not held as one SSE response.
 *
 * Cloudflare Quick Tunnels buffer `text/event-stream` until the origin closes
 * it, so the held stream this socket used to open delivered the whole turn in
 * one burst at the end — the phone showed nothing while it was being written.
 * The cursor poll is what makes each slice arrive while the turn is running.
 */
class RunSocketPollTest {
    private fun event(seq: Long, kind: String, data: String): String =
        "id: $seq\nevent: $kind\ndata: {\"seq\":$seq,\"kind\":\"$kind\",\"data\":$data}\n\n"

    private fun field(socket: RunSocket, name: String): Any? =
        RunSocket::class.java.getDeclaredField(name).apply { isAccessible = true }.get(socket)

    private fun setField(socket: RunSocket, name: String, value: Any?) {
        RunSocket::class.java.getDeclaredField(name).apply { isAccessible = true }.set(socket, value)
    }

    @Test
    fun `a running turn arrives in cursor slices and stops at done`() = runBlocking {
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val respond: (String) -> String = { target ->
            val after = Regex("after=(\\d+)").find(target)?.groupValues?.get(1)?.toLong() ?: 0
            buildString {
                if (after < 7) {
                    append(event(1, "user", """{"text":"hi"}"""))
                    append(event(2, "status", """{"status":"running"}"""))
                    append(event(7, "assistant", """{"text":"partial"}"""))
                } else {
                    append(event(8, "assistant", """{"text":" more"}"""))
                    append(event(9, "status", """{"status":"done"}"""))
                }
            }
        }
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val worker = thread(name = "poll-test-http", isDaemon = true) {
            while (!server.isClosed) {
                try {
                    server.accept().use { client ->
                        val reader = client.getInputStream().bufferedReader()
                        val requestLine = reader.readLine().orEmpty()
                        while (true) {
                            val header = reader.readLine() ?: break
                            if (header.isEmpty()) break
                        }
                        val target = requestLine.split(" ").getOrNull(1).orEmpty()
                        seen += target
                        val body = respond(target).toByteArray()
                        val head = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/event-stream; charset=utf-8\r\n" +
                            "Content-Length: ${body.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        client.getOutputStream().apply {
                            write(head.toByteArray())
                            write(body)
                            flush()
                        }
                    }
                } catch (e: Exception) {
                    if (server.isClosed) break
                }
            }
        }
        try {
            val base = "http://127.0.0.1:${server.localPort}"
            val settings: SettingsStore = mock()
            val context: Context = mock()
            val preferences: SharedPreferences = mock()
            val editor: SharedPreferences.Editor = mock()
            whenever(settings.current()).thenReturn(ConnConfig("", base, "tok", "", "", "P"))
            whenever(context.applicationContext).thenReturn(context)
            whenever(context.getSharedPreferences(any<String>(), eq(Context.MODE_PRIVATE))).thenReturn(preferences)
            whenever(preferences.edit()).thenReturn(editor)
            whenever(editor.remove(any())).thenReturn(editor)
            val socket = RunSocket(settings, context)
            val frames = Collections.synchronizedList(mutableListOf<AiOutput>())
            try {
                val collector = launch { socket.frames.collect { frames += it } }
                // The flow has no replay: let the collector attach before the
                // first slice is emitted.
                delay(50)
                setField(socket, "session", "P")
                setField(socket, "runSession", "P")
                setField(socket, "runId", "run-P")
                setField(socket, "lastSeq", 0L)
                setField(socket, "terminal", false)
                RunSocket::class.java.getDeclaredMethod("startStream", String::class.java)
                    .apply { isAccessible = true }
                    .invoke(socket, "run-P")

                val finished = withTimeoutOrNull(5_000) {
                    while (frames.none { it.kind == "done" }) delay(20)
                    true
                }
                assertTrue("the turn should finish", finished == true)
                assertEquals(
                    listOf("partial", " more"),
                    frames.filter { it.kind == "delta" }.map { it.text },
                )
                assertTrue("every request must ask for a poll", seen.isNotEmpty() && seen.all { it.contains("poll=1") })
                assertEquals("the second slice is asked for after the first", 2, seen.size)
                assertTrue("the cursor carries what was applied", seen[1].contains("after=7"))
                collector.cancel()
            } finally {
                socket.close()
                (field(socket, "scope") as CoroutineScope).cancel()
            }
        } finally {
            server.close()
            worker.join(2_000)
        }
    }

    /**
     * The engine answers while its rows are still on the way to the store and
     * says so on its final event. The screen settles the turn on the closing
     * status frame, so that truth has to travel from one event to the other:
     * a done frame that claims the rows are stored is a turn the phone never
     * reads back, and a thread left on the seq the phone invented for itself.
     */
    @Test
    fun `the closing frame carries what the final event said about the store`() = runBlocking {
        val settings: SettingsStore = mock()
        val context: Context = mock()
        val preferences: SharedPreferences = mock()
        val editor: SharedPreferences.Editor = mock()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.getSharedPreferences(any<String>(), eq(Context.MODE_PRIVATE))).thenReturn(preferences)
        whenever(preferences.edit()).thenReturn(editor)
        whenever(editor.remove(any())).thenReturn(editor)
        val socket = RunSocket(settings, context)
        val frames = Collections.synchronizedList(mutableListOf<AiOutput>())
        try {
            val collector = launch { socket.frames.collect { frames += it } }
            delay(50)
            setField(socket, "session", "P")
            setField(socket, "runSession", "P")
            setField(socket, "runId", "run-P")
            val handle = RunSocket::class.java
                .getDeclaredMethod("handle", String::class.java, String::class.java, String::class.java)
                .apply { isAccessible = true }
            handle.invoke(
                socket, "run-P", "final",
                // The shape the daemon really writes: the turn and its usage,
                // numbered zero because no store has kept it yet.
                """{"seq":1,"kind":"final","data":{"turn":{"seq":0,"role":"assistant","text":"answer"},"usage":{}}}""",
            )
            handle.invoke(socket, "run-P", "status", """{"seq":2,"kind":"status","data":{"status":"done"}}""")
            val closed = withTimeoutOrNull(2_000) {
                while (frames.none { it.kind == "done" }) delay(20)
                true
            }
            assertTrue("the closing frame should reach the screen", closed == true)
            assertEquals("answer", frames.first { it.kind == "message" }.text)
            assertTrue("the rows were not stored yet", !frames.first { it.kind == "done" }.turnsSynced)
            collector.cancel()
        } finally {
            socket.close()
            (field(socket, "scope") as CoroutineScope).cancel()
        }
    }
}
