package com.tulipskun.aixodia.data.remote

import com.tulipskun.aixodia.ConnConfig
import com.tulipskun.aixodia.SettingsStore
import com.tulipskun.aixodia.data.model.AiOutput
import com.tulipskun.aixodia.data.model.AttachmentRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Discovery replaces the daemon address on its own, so the only decision worth
 * being sure about is when *not* to.
 *
 * The Named Tunnel URL is stable across engine restarts. Discovery adopts a
 * configured endpoint without needing a persisted liveness timestamp.
 *
 * And since the label on the settings screen is a claim about this phone, the
 * row is only half of it: the transport decides the other half.
 */
class DaemonDiscoveryTest {

    private fun node(url: String, online: Boolean, version: String = "abc1234") =
        NodeInfo(tunnelUrl = url, version = version, online = online)

    @Test
    fun `a configured endpoint at a new url is adopted`() {
        val action = DaemonDiscovery.decide(
            currentUrl = "https://old.example.com",
            node = node("https://aixodia.dpdns.org", online = true),
        )
        assertEquals(Discovery.Adopt("https://aixodia.dpdns.org"), action)
    }

    @Test
    fun `the url already in use is not rewritten`() {
        // Writing it again would reset the socket's backoff on every poll and
        // make the connection flap for no reason.
        val action = DaemonDiscovery.decide(
            currentUrl = "https://aixodia.dpdns.org",
            node = node("https://aixodia.dpdns.org", online = true),
        )
        assertEquals(Discovery.Keep, action)
    }

    @Test
    fun `a trailing slash is the same address`() {
        val action = DaemonDiscovery.decide(
            currentUrl = "https://aixodia.dpdns.org",
            node = node("https://aixodia.dpdns.org/", online = true),
        )
        assertEquals(Discovery.Keep, action)
    }

    @Test
    fun `a missing live connection does not make the stored endpoint disappear`() {
        // Online state comes from the actual socket; KV only stores configuration.
        val action = DaemonDiscovery.decide(
            currentUrl = "https://old.example.com",
            node = node("https://dead.example.com", online = false),
        )
        assertEquals(Discovery.Wait, action)
    }

    @Test
    fun `an empty row is waited on, not treated as an address`() {
        assertEquals(Discovery.Wait, DaemonDiscovery.decide("https://old.example.com", null))
        assertEquals(
            Discovery.Wait,
            DaemonDiscovery.decide("https://old.example.com", node("", online = false)),
        )
    }

    @Test
    fun `a blank url is not an address even when the endpoint is marked available`() {
        val action = DaemonDiscovery.decide(
            currentUrl = "https://old.example.com",
            node = NodeInfo(tunnelUrl = "   ", version = "abc", online = true),
        )
        assertEquals(Discovery.Wait, action)
    }

    @Test
    fun `discovery keeps the address it has when KV has no endpoint`() {
        var stored = "https://old.example.com"
        val actions = listOf(
            DaemonDiscovery.decide(stored, null),
            DaemonDiscovery.decide(stored, node("https://dead.example.com", online = false)),
            DaemonDiscovery.decide(stored, node("", online = false)),
        )
        for (action in actions) {
            if (action is Discovery.Adopt) stored = action.url
        }
        assertEquals("https://old.example.com", stored)
        assertTrue(actions.none { it is Discovery.Adopt })
    }

    @Test
    fun `a configured endpoint this phone cannot reach is not a connection`() {
        // The chain the settings screen reads: decide() says the row's address
        // is the one in use — that is all the row can ever say — and the
        // transport decides whether to call it connected.
        val action = DaemonDiscovery.decide(
            currentUrl = "https://aixodia.dpdns.org",
            node = node("https://aixodia.dpdns.org", online = true),
        )
        assertEquals(Discovery.Keep, action)
        assertEquals(
            DiscoveryPhase.ANNOUNCED,
            DaemonDiscovery.phaseFor(DiscoveryPhase.CONNECTED, ConnState.OFFLINE),
        )
        assertEquals(
            "a link that is up is the only case the row gets to claim",
            DiscoveryPhase.CONNECTED,
            DaemonDiscovery.phaseFor(DiscoveryPhase.CONNECTED, ConnState.ONLINE),
        )
        // Refused credentials answer, which is not the same as reaching it.
        assertEquals(
            DiscoveryPhase.ANNOUNCED,
            DaemonDiscovery.phaseFor(DiscoveryPhase.CONNECTED, ConnState.AUTH_FAILED),
        )
        assertEquals(
            DiscoveryPhase.ANNOUNCED,
            DaemonDiscovery.phaseFor(DiscoveryPhase.CONNECTED, ConnState.CONNECTING),
        )
    }

    @Test
    fun `a row that names nothing usable is not upgraded by a live socket`() {
        // Otherwise a working link would advertise an endpoint KV never named.
        for (row in listOf(DiscoveryPhase.IDLE, DiscoveryPhase.NO_TOKEN, DiscoveryPhase.UNREACHABLE)) {
            for (transport in ConnState.entries) {
                assertEquals(row, DaemonDiscovery.phaseFor(row, transport))
            }
        }
    }

    @Test
    fun `the label follows the socket instead of waiting for the next poll`() = runBlocking {
        // Workers KV is read every thirty seconds, and a link can drop or come
        // back inside that window. The settings line answers a question about
        // now, so the socket's own state is what redraws it.
        val settings: SettingsStore = mock()
        whenever(settings.current()).thenReturn(
            ConnConfig("", "https://aixodia.dpdns.org", "A", "", "", "s"),
        )
        val history: HistoryApi = mock()
        whenever(history.node()).thenReturn(node("https://aixodia.dpdns.org", online = true))
        val transport = FakeTransport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val discovery = DaemonDiscovery(settings, history, transport, scope)
        try {
            discovery.start()
            transport.state.value = ConnState.OFFLINE
            assertTrue(
                "the row was read; the phone was not",
                awaitUntil { discovery.state.value.phase == DiscoveryPhase.ANNOUNCED },
            )
            assertEquals("https://aixodia.dpdns.org", discovery.state.value.tunnelUrl)
            assertEquals("abc1234", discovery.state.value.version)
            assertTrue(
                "an announced engine this phone cannot dial is not online",
                !discovery.state.value.online,
            )

            transport.state.value = ConnState.ONLINE
            assertTrue(
                "the link came up and nobody re-read KV",
                awaitUntil { discovery.state.value.phase == DiscoveryPhase.CONNECTED },
            )
            assertTrue(discovery.state.value.online)

            transport.state.value = ConnState.OFFLINE
            assertTrue(
                "a link that dropped must not stay green for half a minute",
                awaitUntil { discovery.state.value.phase == DiscoveryPhase.ANNOUNCED },
            )
        } finally {
            scope.cancel()
        }
    }

    private suspend fun awaitUntil(millis: Long = 3_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(25)
        }
        return condition()
    }

    /** The chat transport as discovery sees it: a state it reads and nothing else. */
    private class FakeTransport : ChatTransport {
        override val state = MutableStateFlow(ConnState.OFFLINE)
        override val lastError = MutableStateFlow(0)
        private val _frames = MutableSharedFlow<AiOutput>(extraBufferCapacity = 8)
        override val frames: SharedFlow<AiOutput> = _frames.asSharedFlow()

        override fun open(sessionId: String, fromSeq: Long) {}
        override fun switchSession(sessionId: String) {}
        override fun cancel(sessionId: String): Boolean = false
        override fun close() {}
        override fun reconnect() {}
        override fun retry(sessionId: String, clientMsgId: String): Boolean = false
        override fun send(
            sessionId: String,
            text: String,
            clientMsgId: String,
            attachments: List<AttachmentRef>,
        ): Boolean = false
    }
}
