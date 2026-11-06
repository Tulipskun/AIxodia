package com.tulipskun.aixodia.data.remote

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A daemon on localhost that answers by status code.
 *
 * The states worth being sure about are the ones only an HTTP reply can tell
 * apart — refused, gone, sick — and the whole difference is which of them makes
 * the sockets keep dialing. So this is a real socket to a real server, and the
 * server is the twenty lines of HTTP/1.1 the answer needs.
 *
 * Hand-written rather than the JDK's `HttpServer` because the unit-test compile
 * classpath is the Android platform jar, which does not carry `com.sun.*`.
 */
internal class FakeDaemon : AutoCloseable {
    private data class Rule(val prefix: String, val status: Int, val body: String)

    private val rules = CopyOnWriteArrayList<Rule>()
    private val counted = ConcurrentHashMap<String, AtomicInteger>()
    private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))

    /** Where to point `daemonUrl` so the socket dials this thing. */
    val base: String = "http://127.0.0.1:${listener.localPort}"

    init {
        threadOf {
            while (!listener.isClosed) {
                val client = runCatching { listener.accept() }.getOrNull() ?: break
                threadOf { runCatching { answer(client) } }.start()
            }
        }.start()
    }

    /** Answers every request whose path starts with [prefix] with [status]. */
    fun respond(prefix: String, status: Int, body: String = "") {
        rules.add(Rule(prefix, status, body))
    }

    /** How many times a path starting with [prefix] was dialed. */
    fun hits(prefix: String): Int =
        counted.entries.filter { it.key.startsWith(prefix) }.sumOf { it.value.get() }

    private fun answer(client: Socket) {
        client.use {
            val in_ = it.getInputStream().bufferedReader(Charsets.UTF_8)
            val requestLine = in_.readLine() ?: return
            // The request target carries the query string (`?after=0&poll=1` on
            // an events poll); rules and counts are about the path alone.
            val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?') ?: return
            var length = 0
            while (true) {
                val line = in_.readLine() ?: break
                if (line.isEmpty()) break
                if (line.lowercase().startsWith("content-length:")) {
                    length = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            // Drained before answering: a POST whose body is never read leaves
            // the client blocked on a write it cannot finish.
            var drained = 0
            while (drained < length && in_.read() >= 0) drained++

            counted.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
            // Longest prefix wins: an events poll under /v1/runs/{id} must not
            // be answered by the rule for the run itself.
            val rule = rules.filter { path.startsWith(it.prefix) }
                .maxByOrNull { it.prefix.length }
                ?: error("the test daemon has no rule for $path")
            val body = rule.body.toByteArray()
            val head = buildString {
                append("HTTP/1.1 ${rule.status} test\r\n")
                append("Content-Length: ${body.size}\r\n")
                append("Content-Type: application/json\r\n")
                append("Connection: close\r\n\r\n")
            }
            val out = it.getOutputStream()
            out.write(head.toByteArray())
            if (body.isNotEmpty()) out.write(body)
            out.flush()
        }
    }

    private fun threadOf(block: () -> Unit) = Thread {
        runCatching { block() }
    }.apply { isDaemon = true; name = "FakeDaemon" }

    override fun close() {
        runCatching { listener.close() }
    }
}
