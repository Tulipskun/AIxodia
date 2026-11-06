package com.tulipskun.aixodia.data.remote

import com.tulipskun.aixodia.data.model.AiOutput
import com.tulipskun.aixodia.data.model.AttachmentRef
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

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
 * What the chat screen needs from a transport. [RunSocket] implements it: a
 * turn is a POST /v1/runs and its events arrive over SSE. The repository only
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
