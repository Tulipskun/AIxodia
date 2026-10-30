package com.tulipskun.aixodia.data.remote

import android.content.Context
import android.content.SharedPreferences
import com.tulipskun.aixodia.ConnConfig
import com.tulipskun.aixodia.SettingsStore
import com.tulipskun.aixodia.data.model.AiOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Exercise the actual SSE reducer, without dialing a daemon or requiring a device. */
@OptIn(ExperimentalCoroutinesApi::class)
class RunSocketOwnershipTest {
    private fun socketTest(test: suspend (RunSocket, List<AiOutput>) -> Unit) = runTest {
        val settings: SettingsStore = mock()
        val context: Context = mock()
        val preferences: SharedPreferences = mock()
        val editor: SharedPreferences.Editor = mock()
        whenever(settings.current()).thenReturn(ConnConfig("", "", "", "", "", "A"))
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.getSharedPreferences(any<String>(), eq(Context.MODE_PRIVATE))).thenReturn(preferences)
        whenever(preferences.edit()).thenReturn(editor)
        whenever(editor.remove(any())).thenReturn(editor)
        val socket = RunSocket(settings, context)
        val frames = mutableListOf<AiOutput>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            socket.frames.collect { frames += it }
        }
        try {
            selectRun(socket, "A", "run-A")
            test(socket, frames)
        } finally {
            socket.close()
            (field(socket, "scope") as CoroutineScope).cancel()
        }
    }

    private fun field(socket: RunSocket, name: String): Any? =
        RunSocket::class.java.getDeclaredField(name).apply { isAccessible = true }.get(socket)

    private fun setField(socket: RunSocket, name: String, value: Any?) {
        RunSocket::class.java.getDeclaredField(name).apply { isAccessible = true }.set(socket, value)
    }

    private fun selectRun(socket: RunSocket, session: String, run: String) {
        setField(socket, "session", session)
        setField(socket, "runSession", session)
        setField(socket, "runId", run)
        setField(socket, "lastSeq", 0L)
        setField(socket, "terminal", false)
    }

    private fun event(socket: RunSocket, run: String, kind: String, data: String, seq: Long = 1L) {
        RunSocket::class.java.getDeclaredMethod("handle", String::class.java, String::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(socket, run, kind, """{"seq":$seq,"kind":"$kind","data":$data}""")
    }

    @Test
    fun `a current assistant event retains its originating chat`() = socketTest { socket, frames ->
        event(socket, "run-A", "assistant", """{"text":"A answer"}""")
        assertEquals(listOf("A"), frames.map { it.sessionId })
        assertEquals(listOf("A answer"), frames.map { it.text })
    }

    @Test
    fun `a departed SSE reader cannot label its event with the selected chat`() = socketTest { socket, frames ->
        socket.switchSession("B")
        event(socket, "run-A", "assistant", """{"text":"old A answer"}""")
        assertEquals(emptyList<AiOutput>(), frames)
    }

    @Test
    fun `an old run cannot update a newer chat's stream`() = socketTest { socket, frames ->
        socket.switchSession("B")
        selectRun(socket, "B", "run-B")
        event(socket, "run-A", "assistant", """{"text":"old A answer"}""")
        assertEquals(emptyList<AiOutput>(), frames)
        assertEquals(0L, field(socket, "lastSeq"))
    }

    @Test
    fun `a late terminal event cannot clear a newer run`() = socketTest { socket, frames ->
        socket.switchSession("B")
        selectRun(socket, "B", "run-B")
        event(socket, "run-A", "status", """{"status":"done"}""")
        assertEquals("run-B", field(socket, "runId"))
        assertEquals(emptyList<AiOutput>(), frames)
    }

    @Test
    fun `returning to the same chat does not revive a departed run`() = socketTest { socket, frames ->
        socket.switchSession("B")
        socket.switchSession("A")
        selectRun(socket, "A", "new-run-A")
        event(socket, "run-A", "assistant", """{"text":"old A answer"}""")
        assertEquals(emptyList<AiOutput>(), frames)
    }

    @Test
    fun `a current terminal event closes only its own run`() = socketTest { socket, frames ->
        event(socket, "run-A", "status", """{"status":"done"}""")
        assertEquals(null, field(socket, "runId"))
        assertEquals(listOf("A"), frames.map { it.sessionId })
        assertEquals(listOf("done"), frames.map { it.kind })
    }

    @Test
    fun `stop never cancels a run belonging to another chat`() = socketTest { socket, _ ->
        assertFalse(socket.cancel("B"))
        assertEquals("run-A", field(socket, "runId"))
    }
}
