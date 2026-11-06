package com.tulipskun.aixodia.data.remote

import android.content.Context
import android.content.SharedPreferences
import com.tulipskun.aixodia.ConnConfig
import com.tulipskun.aixodia.R
import com.tulipskun.aixodia.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * A refused credential is not a dropped link.
 *
 * Both used to be answered the same way — the state went OFFLINE, the backoff
 * redialed, and the daemon said 401 again, forever, while the reader watched
 * "Connecting". The daemon *answered* that time, and only the token being fixed
 * changes the reply, so the sockets have to stop dialing and say so.
 */
class RunSocketAuthTest {
    /** A socket dialing [daemon]; [remembered] is the run id its cursor holds for chat "s". */
    private fun socket(daemon: FakeDaemon, remembered: String? = null): RunSocket = runBlocking {
        val settings: SettingsStore = mock()
        whenever(settings.current()).thenReturn(ConnConfig("", daemon.base, "A", "", "", "s"))
        val context: Context = mock()
        val preferences: SharedPreferences = mock()
        val editor: SharedPreferences.Editor = mock()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.getSharedPreferences(any<String>(), eq(Context.MODE_PRIVATE)))
            .thenReturn(preferences)
        whenever(preferences.edit()).thenReturn(editor)
        // The exact arguments RunSocket reads its cursor with, rather than
        // matchers: a nullable default of `null` is what the socket passes, and
        // a matcher that quietly refuses it silently skips the resume.
        whenever(preferences.getString("run:s", null)).thenReturn(remembered)
        whenever(editor.putString(any<String>(), any())).thenReturn(editor)
        whenever(editor.remove(any<String>())).thenReturn(editor)
        RunSocket(settings, mock<HistoryApi>(), context)
    }

    private fun openAndSettle(socket: RunSocket, millis: Long) {
        socket.open("s", 0)
        Thread.sleep(millis)
    }

    @Test
    fun `a refused credential stops probing instead of dialling forever`() {
        val daemon = FakeDaemon()
        daemon.respond("/v1/sessions", 401)
        val socket = socket(daemon)
        try {
            openAndSettle(socket, 1_500)
            assertEquals(ConnState.AUTH_FAILED, socket.state.value)
            assertEquals(R.string.offline_auth, socket.lastError.value)
            assertEquals(
                "a 401 gives the same answer whenever it is asked; asking twice is the bug",
                1,
                daemon.hits("/v1/sessions"),
            )
        } finally {
            socket.close()
            daemon.close()
        }
    }

    @Test
    fun `a refused run stream ends instead of backing off into it`() {
        val daemon = FakeDaemon()
        daemon.respond("/v1/sessions", 200, """{"sessions":[]}""")
        daemon.respond("/v1/runs/run-1", 200, """{"run":{"status":"running"}}""")
        daemon.respond("/v1/runs/run-1/events", 403)
        val socket = socket(daemon, remembered = "run-1")
        try {
            openAndSettle(socket, 1_500)
            assertEquals(ConnState.AUTH_FAILED, socket.state.value)
            assertEquals(R.string.offline_auth, socket.lastError.value)
            assertEquals(
                "the stream must stop the moment the daemon refuses it, not redial on a curve",
                1,
                daemon.hits("/v1/runs/run-1/events"),
            )
        } finally {
            socket.close()
            daemon.close()
        }
    }

    @Test
    fun `a daemon that is only sick is still retried`() {
        // The other half of the fix: a 5xx is a link that may come back, and
        // stopping for that would leave the chat dead after a tunnel blip.
        val daemon = FakeDaemon()
        daemon.respond("/v1/sessions", 200, """{"sessions":[]}""")
        daemon.respond("/v1/runs/run-1", 200, """{"run":{"status":"running"}}""")
        daemon.respond("/v1/runs/run-1/events", 503)
        val socket = socket(daemon, remembered = "run-1")
        try {
            openAndSettle(socket, 3_500)
            assertEquals(ConnState.OFFLINE, socket.state.value)
            assertEquals(0, socket.lastError.value)
            assertTrue(
                "a 503 is not a refusal, so the backoff has to keep dialing",
                daemon.hits("/v1/runs/run-1/events") >= 2,
            )
        } finally {
            socket.close()
            daemon.close()
        }
    }

    @Test
    fun `a run that is gone keeps its own answer, not an auth failure`() {
        val daemon = FakeDaemon()
        daemon.respond("/v1/sessions", 200, """{"sessions":[]}""")
        daemon.respond("/v1/runs/run-1", 200, """{"run":{"status":"running"}}""")
        daemon.respond("/v1/runs/run-1/events", 404)
        val socket = socket(daemon, remembered = "run-1")
        try {
            openAndSettle(socket, 1_500)
            assertEquals(ConnState.ONLINE, socket.state.value)
            assertEquals(0, socket.lastError.value)
            assertEquals(1, daemon.hits("/v1/runs/run-1/events"))
        } finally {
            socket.close()
            daemon.close()
        }
    }
}
