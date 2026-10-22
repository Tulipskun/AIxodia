package com.tulipskun.aixodia.data.remote

import com.tulipskun.aixodia.data.model.AiOutput
import com.tulipskun.aixodia.data.model.AttachmentRef
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the chat screen needs from a transport. Today there are two:
 * [AiDirectSocket], the old socket where one WebSocket carried chat, terminal
 * and RPC, and [RunSocket], where a turn is a POST /v1/runs and its events
 * arrive over SSE. The repository only knows this interface, so switching does
 * not rewrite the merge logic or the UI.
 */
interface ChatTransport {
    val state: StateFlow<ConnState>
    /** A string resource id (0 = nothing wrong). */
    val lastError: StateFlow<Int>
    val frames: SharedFlow<AiOutput>

    /** The tool call waiting for a human decision, if any. */
    val approval: StateFlow<ApprovalRequest?>

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

    /** Answers the pending call. False means it was already answered or gone. */
    fun approve(runId: String, callId: String, allow: Boolean, reason: String = ""): Boolean
}

/** One mutating tool waiting for the user. */
data class ApprovalRequest(
    val runId: String,
    val sessionId: String,
    val callId: String,
    val name: String,
    val arguments: String,
)
