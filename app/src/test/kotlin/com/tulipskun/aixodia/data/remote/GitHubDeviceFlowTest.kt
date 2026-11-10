package com.tulipskun.aixodia.data.remote

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GitHub Device Flow is the sign-in path that removes the stored
 * Cloudflare token from the phone. These cases pin the state machine GitHub
 * documents: pending keeps waiting, slow_down lengthens the interval, and the
 * terminal errors surface as a [DeviceFlowException] a person can act on —
 * all without touching the network.
 */
class GitHubDeviceFlowTest {

    /** Scripted transport: a queue of (status, body) answers per URL. */
    private class FakePost {
        val calls = mutableListOf<Pair<String, String>>()
        val answers = ArrayDeque<Pair<Int, String>>()
        suspend fun post(url: String, body: String): Pair<Int, String> {
            calls.add(url to body)
            return answers.removeFirst()
        }
    }

    private fun flow(post: FakePost, clock: () -> Long = { 0L }) = GitHubDeviceFlow(
        post = post::post,
        now = clock,
        wait = { /* instant in tests */ },
    )

    @Test
    fun `requestDeviceCode parses the complete answer`() = runBlocking {
        val post = FakePost().apply {
            answers.add(
                200 to """{"device_code":"DC1","user_code":"ABCD-1234",""" +
                    """"verification_uri":"https://github.com/login/device",""" +
                    """"expires_in":600,"interval":3}""",
            )
        }
        val info = flow(post).requestDeviceCode("CLIENT_ID")
        assertEquals("DC1", info.deviceCode)
        assertEquals("ABCD-1234", info.userCode)
        assertEquals("https://github.com/login/device", info.verificationUri)
        assertEquals(600L, info.expiresInSec)
        assertEquals(3L, info.intervalSec)
        // The request carries the client id and the scope.
        val sent = JSONObject(post.calls.single().second)
        assertEquals("CLIENT_ID", sent.getString("client_id"))
        assertEquals("read:user", sent.getString("scope"))
    }

    @Test
    fun `requestDeviceCode tolerates a form-encoded answer`() = runBlocking {
        val post = FakePost().apply {
            answers.add(200 to "device_code=DC2&user_code=XXXX-XXXX&verification_uri=https%3A%2F%2Fgithub.com%2Flogin%2Fdevice")
        }
        val info = flow(post).requestDeviceCode("CLIENT_ID")
        assertEquals("DC2", info.deviceCode)
        assertEquals("XXXX-XXXX", info.userCode)
        assertEquals(900L, info.expiresInSec) // documented default
    }

    @Test
    fun `requestDeviceCode rejects an incomplete answer`() = runBlocking {
        val post = FakePost().apply { answers.add(200 to """{"foo":1}""") }
        try {
            flow(post).requestDeviceCode("CLIENT_ID")
            throw AssertionError("expected DeviceFlowException")
        } catch (e: DeviceFlowException) {
            assertEquals("INVALID_DEVICE_CODE_RESPONSE", e.code)
        }
        Unit
    }

    @Test
    fun `poll waits through pending and slow_down then returns the token`() = runBlocking {
        val post = FakePost().apply {
            answers.add(200 to """{"error":"authorization_pending"}""")
            answers.add(200 to """{"error":"slow_down"}""")
            answers.add(200 to """{"access_token":"GH_TOKEN","token_type":"bearer"}""")
        }
        val info = DeviceCodeInfo("DC", "UUUU-UUUU", "https://github.com/login/device", 900, 5)
        val token = flow(post).pollForToken("CLIENT_ID", info)
        assertEquals("GH_TOKEN", token)
        assertEquals(3, post.calls.size)
        // Every poll carries the device code and the device grant type.
        val sent = JSONObject(post.calls.first().second)
        assertEquals("DC", sent.getString("device_code"))
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", sent.getString("grant_type"))
    }

    @Test
    fun `poll throws a readable error on access_denied`() = runBlocking {
        val post = FakePost().apply {
            answers.add(200 to """{"error":"access_denied","error_description":"The user has denied the application"}""")
        }
        val info = DeviceCodeInfo("DC", "UUUU-UUUU", "https://github.com/login/device", 900, 5)
        try {
            flow(post).pollForToken("CLIENT_ID", info)
            throw AssertionError("expected DeviceFlowException")
        } catch (e: DeviceFlowException) {
            assertEquals("access_denied", e.code)
            assertTrue(e.message!!.contains("denied", ignoreCase = true) || e.message!!.contains("ปฏิเสธ"))
        }
        Unit
    }

    @Test
    fun `poll expires once the device code lapses`() = runBlocking {
        val post = FakePost()
        var clock = 0L
        repeat(50) { post.answers.add(200 to """{"error":"authorization_pending"}""") }
        val f = GitHubDeviceFlow(
            post = post::post,
            now = { clock },
            wait = { clock += 60_000L }, // each wait burns a minute
        )
        val info = DeviceCodeInfo("DC", "UUUU-UUUU", "https://github.com/login/device", 5, 5)
        try {
            f.pollForToken("CLIENT_ID", info)
            throw AssertionError("expected DeviceFlowException")
        } catch (e: DeviceFlowException) {
            assertEquals("expired_token", e.code)
        }
        Unit
    }

    @Test
    fun `a network failure maps to NETWORK_ERROR`() = runBlocking {
        val f = GitHubDeviceFlow(
            post = { _, _ -> throw java.io.IOException("dead link") },
            wait = { },
        )
        try {
            f.requestDeviceCode("CLIENT_ID")
            throw AssertionError("expected DeviceFlowException")
        } catch (e: DeviceFlowException) {
            assertEquals("NETWORK_ERROR", e.code)
        }
        Unit
    }
}
