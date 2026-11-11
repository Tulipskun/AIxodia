package com.tulipskun.aixodia.data.remote

import android.content.Context
import android.content.SharedPreferences
import com.tulipskun.aixodia.ConnConfig
import com.tulipskun.aixodia.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * A reconnect is a promise that something changed, so it has to be acted on.
 *
 * The address the daemon announced, or an engine that just came back: either
 * way [RunSocket.reconnect] proved a dial works and then left the run stream to
 * notice on its own schedule — the failed poll was asleep in a backoff that the
 * curve had already grown to 15s, so the chat stared at "Sending…" through the
 * wait while a working tunnel was sitting there. Waking the wait is the whole
 * fix; the curve restarting at a second is what keeps a wrong address from being
 * dialled hundreds of times while it lasts.
 *
 * Timings are real sleeps, as in the other socket tests: what is being asserted
 * is how long a reader waits, and virtual time would not have noticed the bug.
 */
class RunSocketReconnectTest {
    /** A socket dialing [daemon]; [remembered] is the run id its cursor holds for chat "s". */
    private fun socket(daemon: FakeDaemon, remembered: String? = "run-1"): RunSocket = runBlocking {
        val settings: SettingsStore = mock()
        whenever(settings.current()).thenReturn(ConnConfig("", daemon.base, "", "", "", "s", accessCookie = "A"))
        val context: Context = mock()
        val preferences: SharedPreferences = mock()
        val editor: SharedPreferences.Editor = mock()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.getSharedPreferences(any<String>(), eq(Context.MODE_PRIVATE)))
            .thenReturn(preferences)
        whenever(preferences.edit()).thenReturn(editor)
        whenever(preferences.getString("run:s", null)).thenReturn(remembered)
        whenever(editor.putString(any<String>(), any())).thenReturn(editor)
        whenever(editor.remove(any<String>())).thenReturn(editor)
        RunSocket(settings, mock<HistoryApi>(), context)
    }

    private fun streamJob(socket: RunSocket): Any? =
        RunSocket::class.java.getDeclaredField("streamJob").apply { isAccessible = true }.get(socket)

    /** A daemon that answers the probe and the run, and is sick on the event poll. */
    private fun sickEvents(): FakeDaemon {
        val daemon = FakeDaemon()
        daemon.respond("/v1/sessions", 200, """{"sessions":[]}""")
        daemon.respond("/v1/runs/run-1", 200, """{"run":{"status":"running"}}""")
        daemon.respond("/v1/runs/run-1/events", 503)
        return daemon
    }

    private fun polls(daemon: FakeDaemon): Int = daemon.hits("/v1/runs/run-1/events")

    /** Waits until the predicate holds, returning how many milliseconds it took, or null. */
    private fun waitedUntil(millis: Long, predicate: () -> Boolean): Long? {
        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt < millis) {
            if (predicate()) return System.currentTimeMillis() - startedAt
            Thread.sleep(10)
        }
        return null
    }

    /** Blocks until the failing poll has been dialled once more; returns that count. */
    private fun afterNextPoll(daemon: FakeDaemon, millis: Long = 6_000): Int {
        val before = polls(daemon)
        val seen = waitedUntil(millis) { polls(daemon) > before }
        assertTrue("the failing poll should dial again within ${millis}ms", seen != null)
        return polls(daemon)
    }

    @Test
    fun `a reconnect cuts short the backoff a failed poll fell into`() {
        val daemon = sickEvents()
        val socket = socket(daemon)
        try {
            socket.open("s", 0)
            // The wait is caught at its start. No step of the curve is shorter
            // than a second, so a poll inside 400ms can only be the wake: left
            // alone, this dial is the next-but-one.
            val before = afterNextPoll(daemon)
            socket.reconnect()
            val woken = waitedUntil(400) { polls(daemon) > before }
            assertTrue(
                "a reconnect that found the daemon must be dialled now, not when the backoff ends",
                woken != null,
            )
        } finally {
            socket.close()
            daemon.close()
        }
    }

    @Test
    fun `the backoff starts again from a second after a wake`() {
        val daemon = sickEvents()
        val socket = socket(daemon)
        try {
            socket.open("s", 0)
            val before = afterNextPoll(daemon)
            socket.reconnect()
            assertTrue("the wake should be dialled", waitedUntil(400) { polls(daemon) > before } != null)
            // That woken poll failed like the others, so the dial after it says
            // which curve it is on: a second for a wake that restarted the
            // backoff, two for one that only cut this wait short.
            val afterWake = polls(daemon)
            val again = waitedUntil(1_600) { polls(daemon) > afterWake }
            assertTrue(
                "a wake has to reset the curve, otherwise a wrong address is dialled on a grown wait",
                again != null,
            )
        } finally {
            socket.close()
            daemon.close()
        }
    }

    @Test
    fun `a live stream is woken instead of joined by a second one`() {
        val daemon = sickEvents()
        val socket = socket(daemon)
        try {
            socket.open("s", 0)
            val before = afterNextPoll(daemon)
            val job = streamJob(socket)
            assertTrue("a run stream should be running", (job as? Job)?.isActive == true)

            socket.reconnect()
            assertTrue("the wake should be dialled", waitedUntil(400) { polls(daemon) > before } != null)
            assertTrue(
                "two readers on one run replay the same deltas into the turn",
                streamJob(socket) === job,
            )
        } finally {
            socket.close()
            daemon.close()
        }
    }
}
