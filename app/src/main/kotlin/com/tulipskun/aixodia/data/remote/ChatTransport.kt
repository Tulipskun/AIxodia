package com.tulipskun.aixodia.data.remote

import com.tulipskun.aixodia.data.model.AiOutput
import com.tulipskun.aixodia.data.model.AttachmentRef
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToLong

/**
 * The connection state the chrome draws.
 *
 * [AUTH_FAILED] is its own state because it needs its own treatment: the daemon
 * answered and refused the credential, so the link is up and nothing a retry can
 * do will change the answer. Only the reader fixing the token changes it.
 */
enum class ConnState { OFFLINE, CONNECTING, ONLINE, AUTH_FAILED }

/** The daemon answered and refused this credential. Another dial says the same. */
internal fun isAuthFailure(code: Int): Boolean = code == 401 || code == 403

/**
 * A retry wait spread by up to ±20%, from [draw] in `0..1` — 0 the short end,
 * 0.5 the wait itself, 1 the long end.
 *
 * Every phone that lost the same tunnel runs the same curve, so a backoff alone
 * has the whole room redial on the same second: whoever comes back is met by
 * everyone at once, and the next drop is synchronised again. The spread is only
 * ever on the wait, never on the curve itself, so it cannot compound into the
 * growth. A draw is handed in rather than taken here because a test has to be
 * able to name both ends of the range.
 */
internal fun jitteredBackoff(millis: Long, draw: Double): Long {
    val spread = 1.0 + 0.2 * (draw * 2.0 - 1.0)
    return (millis * spread).roundToLong().coerceAtLeast(1L)
}

/**
 * What the chat screen needs from a transport. [RunSocket] implements it: a
 * turn is a POST /api/runs and its events arrive over SSE. The repository only
 * knows this interface, so the merge logic and the UI are independent of the
 * wire format.
 */
interface ChatTransport {
    val state: StateFlow<ConnState>
    /** A string resource id (0 = nothing wrong). */
    val lastError: StateFlow<Int>
    val frames: SharedFlow<AiOutput>

    fun open(sessionId: String, fromSeq: Long)
    fun switchSession(sessionId: String)
    fun cancel(sessionId: String): Boolean
    fun close()
    fun reconnect()
    fun retry(sessionId: String, clientMsgId: String): Boolean
    fun send(
        sessionId: String,
        text: String,
        clientMsgId: String,
        attachments: List<AttachmentRef> = emptyList(),
    ): Boolean
}
