package com.tulipskun.aixodia.data.remote

import com.tulipskun.aixodia.SettingsStore
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.net.URI

/**
 * Carries every request aimed at the engine over the relay socket instead of
 * opening a TCP connection.
 *
 * The engine publishes a relay room address (an https URL) and the app used to
 * call it directly; behind a relay there is no inbound address to call, so the
 * same request is handed to [RelayClient] as an rpc frame and the engine
 * answers from inside its own process. D1 calls go to Cloudflare, not the room,
 * and pass straight through.
 */
class RelayInterceptor(
    private val settings: SettingsStore,
    private val relay: RelayClient?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val r = relay ?: return chain.proceed(req)
        if (!r.isOnline) return chain.proceed(req)

        val daemonUrl = runCatching { runBlocking { settings.current().daemonUrl } }.getOrNull()
        val path = daemonUrl?.let { relayPath(req.url.toString(), it) } ?: return chain.proceed(req)
        val body = req.body?.let { b ->
            val buffer = Buffer()
            b.writeTo(buffer)
            buffer.readByteArray()
        }
        val headers = req.headers.toMultimap().mapValues { it.value.last() }
        val reply = runBlocking { r.rpc(req.method, path, body, headers) }
        return Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(reply.code)
            .message(reply.reason.ifBlank { "relay" })
            .headers(Headers.Builder().apply {
                reply.headers.forEach { (k, v) -> add(k, v) }
            }.build())
            .body(reply.body.toResponseBody("application/json".toMediaType()))
            .build()
    }
}

/**
 * The path the engine's mux knows: the request URL minus the room prefix the
 * relay address carries. Null when the URL is not that engine's.
 */
internal fun relayPath(url: String, daemonUrl: String): String? {
    val u = runCatching { URI(url) }.getOrNull() ?: return null
    val base = runCatching { URI(daemonUrl.trim().trimEnd('/')) }.getOrNull() ?: return null
    if (u.host != base.host) return null
    val prefix = base.path.orEmpty().trimEnd('/')
    val path = u.path.orEmpty()
    if (prefix.isNotEmpty() && path != prefix && !path.startsWith("$prefix/")) return null
    val rest = path.removePrefix(prefix).ifEmpty { "/" }
    val query = u.rawQuery
    return if (query.isNullOrEmpty()) rest else "$rest?$query"
}
