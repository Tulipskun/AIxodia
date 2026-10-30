package com.tulipskun.aixodia.data.remote

import android.content.Context
import android.content.SharedPreferences
import com.tulipskun.aixodia.SettingsStore
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The one property the live turn depends on: a run stream that stops carrying
 * bytes has to fail, not be waited on forever. An infinite read timeout let an
 * silently-dropped tunnel stream park the screen on "Sending request to
 * provider…" with no `done` frame ever arriving and no reconnect.
 */
class RunSocketStreamTest {
    private fun client(): OkHttpClient {
        val settings: SettingsStore = mock()
        val context: Context = mock()
        val preferences: SharedPreferences = mock()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.getSharedPreferences(any<String>(), eq(Context.MODE_PRIVATE))).thenReturn(preferences)
        return RunSocket(settings, context).let { socket ->
            val client = RunSocket::class.java.getDeclaredField("streamHttp")
                .apply { isAccessible = true }
                .get(socket) as OkHttpClient
            socket.close()
            client
        }
    }

    @Test
    fun `a stalled run stream is timed out instead of waited on forever`() {
        val client = client()
        assertTrue(
            "an infinite read timeout cannot notice a stream the tunnel dropped",
            client.readTimeoutMillis > 0,
        )
        assertEquals(STREAM_READ_TIMEOUT_MS.toInt(), client.readTimeoutMillis)
    }

    @Test
    fun `the timeout leaves a heartbeat of a healthy stream plenty of room`() {
        val heartbeatMs = 15_000
        // Three heartbeats are missed before the read fails: a slow provider is
        // silent for far less than that on a stream that is really alive.
        assertTrue(
            "a timeout tighter than the daemon's heartbeat would cut off live turns",
            client().readTimeoutMillis >= 3 * heartbeatMs,
        )
    }

    @Test
    fun `a turn longer than any read gap is never cut off wholesale`() {
        // The read timeout bounds silence, not the run: a whole-call timeout
        // would kill a ten-minute turn between its very tokens.
        assertEquals(0, client().callTimeoutMillis)
    }
}
