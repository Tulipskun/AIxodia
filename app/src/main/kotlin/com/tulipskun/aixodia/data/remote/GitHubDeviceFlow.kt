package com.tulipskun.aixodia.data.remote

import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import kotlin.random.Random

/** One device-flow session as GitHub answered step 1. */
data class DeviceCodeInfo(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSec: Long,
    val intervalSec: Long,
)

/** A terminal device-flow failure; [code] is GitHub's error string when known. */
class DeviceFlowException(val code: String, message: String) : Exception(message)

/**
 * GitHub Device Flow sign-in: the app asks GitHub for a short user code, the
 * person enters it at [VERIFICATION_URI] in any browser, and GitHub hands back
 * an OAuth access token. That token is exchanged at the engine for a
 * short-lived Engine session token. The GitHub access token is used only in
 * memory for that exchange and is never persisted by the app.
 *
 * The whole flow is pure: the HTTP call and the clock are injectable so unit
 * tests can drive the state machine without touching the network, mirroring
 * the dashboard's `client-core.js` implementation.
 */
class GitHubDeviceFlow(
    /** Blocking HTTP POST returning the body text and status; injectable. */
    private val post: suspend (url: String, body: String) -> Pair<Int, String> =
        defaultPost(),
    /** Unix milliseconds; injectable for deterministic expiry tests. */
    private val now: () -> Long = { System.currentTimeMillis() },
    /** Pause between polls; injectable so tests run instantly. */
    private val wait: suspend (ms: Long) -> Unit = { delay(it) },
    private val maxWaits: Int = 200,
) {
    /**
     * Step 1: ask GitHub for a device code. Throws [DeviceFlowException] on a
     * network failure or an incomplete answer.
     */
    suspend fun requestDeviceCode(
        clientId: String,
        scope: String = SCOPE,
    ): DeviceCodeInfo {
        val body = JSONObject().put("client_id", clientId).put("scope", scope).toString()
        val (status, text) = postOrNetworkError(DEVICE_CODE_URL, body)
        val data = parse(status, text)
        val deviceCode = data.optString("device_code").orEmpty()
        val userCode = data.optString("user_code").orEmpty()
        val uri = data.optString("verification_uri").orEmpty()
        if (deviceCode.isBlank() || userCode.isBlank() || uri.isBlank()) {
            throw DeviceFlowException(
                "INVALID_DEVICE_CODE_RESPONSE",
                "GitHub ตอบกลับมาไม่ครบ ลองล็อกอินใหม่อีกครั้ง",
            )
        }
        return DeviceCodeInfo(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = uri,
            expiresInSec = data.optLong("expires_in", 900L).takeIf { it > 0 } ?: 900L,
            intervalSec = data.optLong("interval", 5L).takeIf { it > 0 } ?: 5L,
        )
    }

    /**
     * Step 2: poll until GitHub grants the access token. `authorization_pending`
     * keeps waiting, `slow_down` lengthens the interval, and the terminal
     * errors (`expired_token`, `access_denied`, `incorrect_device_code`) throw
     * a [DeviceFlowException] with a message a person can act on.
     */
    suspend fun pollForToken(clientId: String, info: DeviceCodeInfo): String {
        var interval = info.intervalSec
        val deadline = now() + info.expiresInSec * 1000
        repeat(maxWaits) {
            val body = JSONObject()
                .put("client_id", clientId)
                .put("device_code", info.deviceCode)
                .put("grant_type", GRANT_TYPE)
                .toString()
            val (status, text) = postOrNetworkError(TOKEN_URL, body)
            val data = parse(status, text)
            val token = data.optString("access_token").orEmpty()
            if (token.isNotBlank()) return token
            when (val error = data.optString("error").orEmpty()) {
                "authorization_pending" -> Unit // keep waiting
                "slow_down" -> interval += 5
                else -> throw DeviceFlowException(
                    error.ifBlank { "HTTP_$status" },
                    readableError(error, data.optString("error_description").orEmpty()),
                )
            }
            if (now() >= deadline) {
                throw DeviceFlowException("expired_token", readableError("expired_token", ""))
            }
            wait(jittered(interval))
        }
        throw DeviceFlowException("expired_token", readableError("expired_token", ""))
    }

    private suspend fun postOrNetworkError(url: String, body: String): Pair<Int, String> =
        try {
            post(url, body)
        } catch (e: IOException) {
            throw DeviceFlowException("NETWORK_ERROR", readableError("NETWORK_ERROR", ""))
        } catch (e: DeviceFlowException) {
            throw e
        } catch (e: Exception) {
            throw DeviceFlowException("NETWORK_ERROR", readableError("NETWORK_ERROR", ""))
        }

    /** GitHub answers either JSON or form-urlencoded; accept both. */
    private fun parse(status: Int, text: String): JSONObject {
        if (status !in 200..299 && text.isBlank()) {
            throw DeviceFlowException("HTTP_$status", readableError("HTTP_$status", ""))
        }
        val trimmed = text.trim()
        if (trimmed.startsWith("{")) {
            return runCatching { JSONObject(trimmed) }.getOrElse {
                throw DeviceFlowException("HTTP_$status", readableError("HTTP_$status", ""))
            }
        }
        // Form-encoded fallback: k=v&k=v with URL escaping.
        val out = JSONObject()
        for (pair in trimmed.split('&')) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val key = java.net.URLDecoder.decode(pair.take(idx), "UTF-8")
            val value = java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            out.put(key, value)
        }
        return out
    }

    private fun jittered(baseSec: Long): Long =
        Random.nextLong(baseSec * 1000L, baseSec * 1000L + 150L).coerceAtLeast(1000L)

    companion object {
        const val DEVICE_CODE_URL = "https://github.com/login/device/code"
        const val TOKEN_URL = "https://github.com/login/oauth/access_token"
        const val VERIFICATION_URI = "https://github.com/login/device"
        const val SCOPE = "read:user"
        private const val GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"

        /** Map GitHub device-flow error codes to messages a person can act on. */
        fun readableError(error: String, description: String): String {
            if (description.isNotBlank()) return description
            return when (error) {
                "expired_token" -> "รหัสล็อกอินหมดอายุ ลองเริ่มใหม่อีกครั้ง"
                "access_denied" -> "คุณปฏิเสธการล็อกอินใน GitHub หรือยกเลิกไป"
                "incorrect_device_code" -> "GitHub ปฏิเสธรหัส ลองเริ่มใหม่อีกครั้ง"
                "incorrect_client_credentials" -> "client id ของ GitHub OAuth App ไม่ถูกต้อง"
                "bad_verification_code" -> "รหัสยืนยันไม่ถูกต้อง ลองเริ่มใหม่อีกครั้ง"
                "NETWORK_ERROR" -> "ติดต่อ GitHub ไม่ได้ ตรวจการเชื่อมต่อแล้วลองใหม่"
                "" -> "ล็อกอินผ่าน GitHub ไม่สำเร็จ"
                else -> "GitHub ล็อกอินไม่สำเร็จ ($error)"
            }
        }

        private fun defaultPost(): suspend (String, String) -> Pair<Int, String> {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val json = "application/json".toMediaType()
            return { url, body ->
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .post(body.toRequestBody(json))
                    .build()
                client.newCall(req).execute().use { r ->
                    r.body?.string().orEmpty().let { r.code to it }
                }
            }
        }
    }
}
