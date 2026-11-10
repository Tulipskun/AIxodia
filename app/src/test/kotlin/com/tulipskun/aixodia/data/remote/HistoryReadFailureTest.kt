package com.tulipskun.aixodia.data.remote

import com.tulipskun.aixodia.ConnConfig
import com.tulipskun.aixodia.SettingsStore
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** A real history reader, with only the Engine transport mocked. */
class HistoryReadFailureTest {
    private val settings: SettingsStore = mock()
    private val client: OkHttpClient = mock()
    private val call: Call = mock()

    private fun assertOriginalFailure(expected: Exception, actual: Throwable?) {
        // withContext may copy an exception to recover its coroutine stack.
        // Its original cause must still be preserved, not replaced or swallowed.
        assertEquals(expected.javaClass, actual?.javaClass)
        assertTrue(generateSequence(actual) { it.cause }.any { it === expected })
    }

    private suspend fun api(code: Int = 200, body: String = "{}", url: String = "https://daemon.test"): HistoryApi {
        whenever(settings.current()).thenReturn(ConnConfig("", url, "", "", "", "A", sessionToken = "test-token"))
        whenever(client.newCall(any())).thenReturn(call)
        whenever(call.execute()).thenAnswer {
            Response.Builder().request(Request.Builder().url("https://daemon.test").build())
                .protocol(Protocol.HTTP_1_1).code(code).message("test response")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }
        return HistoryApi(settings, client = client)
    }

    @Test
    fun `an unreachable Engine returns no remote history page`() = runTest {
        val api = api(url = "")
        assertEquals(emptyList<TurnRow>(), api.turns("A", 100, 60))
    }

    @Test
    fun `a cancelled daemon read never starts the D1 fallback`() = runTest {
        val api = api()
        val cancelled = CancellationException("left the chat")
        whenever(call.execute()).thenThrow(cancelled)
        assertOriginalFailure(cancelled, runCatching { api.turns("A", 100, 60) }.exceptionOrNull())
    }

    @Test
    fun `an unsuccessful Engine response is not treated as a successful empty page`() = runTest {
        val api = api(code = 503, body = "{\"turns\":[]}")
        assertEquals(emptyList<TurnRow>(), api.turns("A", 100, 60))
    }

    @Test
    fun `a successful empty page is authoritative`() = runTest {
        val api = api(body = "{\"turns\":[]}")
        assertEquals(emptyList<TurnRow>(), api.turns("A", 100, 60))
    }
}
