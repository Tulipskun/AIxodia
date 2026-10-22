package com.tulipskun.aixodia.data.remote

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.tulipskun.aixodia.SettingsStore
import com.tulipskun.aixodia.data.model.AiInput
import com.tulipskun.aixodia.data.model.AiOutput
import com.tulipskun.aixodia.data.model.ContentPart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class ConnState { OFFLINE, CONNECTING, ONLINE }

/**
 * The chat half of the app's single socket.
 *
 * All the wire work lives in [RelayClient]: one socket carries chat, the
 * terminal and the HTTP API. This class keeps the chat face the rest of the app
 * already speaks — hello/cancel/message frames and the stream of answers — so a
 * screen does not have to know which transport is under it.
 */
class AiDirectSocket(
    settings: SettingsStore,
    private val relay: RelayClient = RelayClient(settings),
) : ChatTransport {
    private val inAdapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        .adapter(AiInput::class.java)

    override val state: StateFlow<ConnState> = relay.state
    override val lastError: StateFlow<Int> = relay.lastError
    override val frames: SharedFlow<AiOutput> = relay.chatFrames
    // The old socket has no pause-for-approval step; the daemon runs tools
    // directly, so there is never anything to answer here.
    override val approval: StateFlow<ApprovalRequest?> = MutableStateFlow(null)

    @Volatile
    private var session: String = "default"

    @Volatile
    private var resumeFrom: Long = 0

    init {
        // The hello tells the engine which chat this phone is showing and where
        // its history ended, so a reconnect lands on the right tail.
        relay.onOpen = { sendHello() }
    }

    private fun sendHello() {
        val hello = inAdapter.toJson(
            AiInput(
                type = "hello",
                sessionId = session,
                content = listOf(ContentPart(text = "resume:$resumeFrom")),
            )
        )
        relay.sendChat(hello)
    }

    /** Starts the single connection (idempotent) for [sessionId]. */
    override fun open(sessionId: String, fromSeq: Long) {
        session = sessionId
        resumeFrom = fromSeq
        relay.connect()
    }

    /** Switches the session used for subsequent sends + reconnects. */
    override fun switchSession(sessionId: String) {
        session = sessionId
    }

    /** The stop button: cancels the turn running in this chat. */
    override fun cancel(sessionId: String): Boolean {
        val frame = AiInput(type = "cancel", sessionId = sessionId)
        return relay.sendChat(inAdapter.toJson(frame))
    }

    /** Closes the socket and tells the engine no turn is still wanted. */
    override fun close() {
        relay.bye()
        relay.disconnect()
    }

    /**
     * Drops the current socket so it dials again immediately. Discovery calls
     * this when the engine's address changed.
     */
    override fun reconnect() {
        relay.reconnect()
    }

    /**
     * Asks the engine to resume the last unfinished question it already has
     * stored. It sends no text: a retry must not create another user bubble or
     * feed the question to the model twice.
     */
    override fun retry(sessionId: String, clientMsgId: String): Boolean {
        val frame = AiInput(type = "retry", sessionId = sessionId, clientMsgId = clientMsgId)
        return relay.sendChat(inAdapter.toJson(frame))
    }

    /** Returns true when the frame was handed to the socket. */
    override fun send(
        sessionId: String,
        text: String,
        clientMsgId: String,
        attachments: List<com.tulipskun.aixodia.data.model.AttachmentRef>,
    ): Boolean {
        val frame = AiInput(
            sessionId = sessionId,
            content = listOf(ContentPart(text = text)),
            clientMsgId = clientMsgId,
            attachments = attachments,
        )
        return relay.sendChat(inAdapter.toJson(frame))
    }

    override fun approve(runId: String, callId: String, allow: Boolean, reason: String): Boolean = false
}
