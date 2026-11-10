package com.tulipskun.aixodia.data.remote

import android.util.Log
import com.tulipskun.aixodia.SettingsStore
import com.tulipskun.aixodia.data.model.AiOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "DaemonDiscovery"

/**
 * What discovery decided to do about the address it just read.
 *
 * The Named Tunnel hostname is stable across engine restarts. The app checks
 * the Engine health endpoint directly; the live socket, not a heartbeat,
 * determines whether the engine is currently reachable.
 */
sealed interface Discovery {
    /** The Engine has not announced a different usable address. */
    data object Wait : Discovery

    /** The Engine announced the address already in use. Nothing to write. */
    data object Keep : Discovery

    /** A different configured daemon endpoint was found. Use its URL. */
    data class Adopt(val url: String) : Discovery
}

/**
 * Keeps the daemon address pointing at whatever kernel is currently serving.
 *
 * The app starts with the stable Named Tunnel URL. Discovery checks the Engine
 * health endpoint and also adopts an updated URL announced on the live socket.
 *
 * Deliberately not a background service: it runs for as long as the app does,
 * which is the only time a phone can be talked to anyway.
 */
class DaemonDiscovery(
    private val settings: SettingsStore,
    private val history: HistoryApi,
    private val socket: ChatTransport,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(DiscoveryState())

    /** What discovery last saw, for the settings screen to show. */
    val state: StateFlow<DiscoveryState> = _state

    /**
     * The engine instance whose start time was last seen. A different started
     * time means the daemon was replaced: every terminal stream the phone had
     * died with the old process, even though the address did not change.
     */
    private var lastStartedAt = 0L

    fun start() {
        scope.launch {
            socket.frames.collect { frame ->
                if (frame.kind == "daemon") handleFrame(frame)
            }
        }
        scope.launch {
            // The row is re-read every thirty seconds, but the half of the label
            // the reader is actually asking about — *can this phone reach it* —
            // changes whenever the socket likes. Republish on every transition,
            // and never re-run the address decision here: that would turn a
            // socket flap into a reconnect loop.
            socket.state.collect { lastRow?.let { _state.value = shownFor(it) } }
        }
        scope.launch {
            while (isActive) {
                try {
                    handleNode(history.node())
                } catch (e: Exception) {
                    Log.w(TAG, "Engine health check failed: ${e.message}")
                    report(DiscoveryPhase.UNREACHABLE, error = e.message?.take(120).orEmpty())
                }
                delay(30_000)
            }
        }
    }

    private suspend fun handleFrame(frame: AiOutput) {
        if (!frame.daemonOnline) return
        handleNode(
            NodeInfo(
                tunnelUrl = frame.daemonUrl,
                version = frame.daemonVersion,
                online = frame.daemonUrl.isNotBlank(),
                startedAt = frame.daemonStarted,
            ),
        )
    }

    private suspend fun handleNode(node: NodeInfo?) {
        val current = try {
            settings.current()
        } catch (e: Exception) {
            Log.w(TAG, "settings read failed: ${e.message}")
            report(DiscoveryPhase.UNREACHABLE, error = e.message?.take(120).orEmpty())
            return
        }
        if (current.sessionToken.isBlank()) {
            report(DiscoveryPhase.NO_TOKEN)
            return
        }
        if (node == null) {
            report(DiscoveryPhase.IDLE, tunnelUrl = current.daemonUrl)
            return
        }

        val started = node.startedAt
        val replaced = started > 0L && lastStartedAt > 0L && started != lastStartedAt
        if (started > 0L) lastStartedAt = started
        when (val action = decide(current.daemonUrl, node)) {
            is Discovery.Adopt -> {
                Log.i(TAG, "daemon endpoint changed: adopting ${action.url}")
                settings.saveDaemon(action.url)
                socket.reconnect()
                report(DiscoveryPhase.CONNECTED, tunnelUrl = action.url, version = node.version)
            }

            Discovery.Keep -> {
                if (replaced) {
                    Log.i(TAG, "engine restarted at $started; redialing socket and terminal")
                    socket.reconnect()
                }
                report(DiscoveryPhase.CONNECTED, tunnelUrl = node.tunnelUrl, version = node.version)
            }

            Discovery.Wait -> report(DiscoveryPhase.IDLE, tunnelUrl = current.daemonUrl)
        }
    }

    /**
     * Records what the row said and publishes it through the socket's answer.
     * The row is only remembered so a later socket transition can be reported
     * without another health request.
     */
    private fun report(
        phase: DiscoveryPhase,
        tunnelUrl: String = "",
        version: String = "",
        error: String = "",
    ) {
        val row = Row(phase, tunnelUrl, version, error)
        lastRow = row
        _state.value = shownFor(row)
    }

    private fun shownFor(row: Row): DiscoveryState {
        val shown = phaseFor(row.phase, socket.state.value)
        return DiscoveryState(
            phase = shown,
            tunnelUrl = row.tunnelUrl,
            version = row.version,
            online = shown == DiscoveryPhase.CONNECTED,
            error = row.error,
        )
    }

    /** The last Engine health result, kept for the socket's side of the label. */
    private data class Row(
        val phase: DiscoveryPhase,
        val tunnelUrl: String,
        val version: String,
        val error: String,
    )

    private var lastRow: Row? = null

    companion object {
        /** Reads a push into a decision. Separate from the frame handling so it can be tested. */
        fun decide(currentUrl: String, node: NodeInfo?): Discovery {
            val url = node?.tunnelUrl?.trim().orEmpty()
            // No endpoint has been configured yet.
            if (url.isEmpty()) return Discovery.Wait
            // A missing endpoint must never erase a working address.
            if (node?.online != true) return Discovery.Wait
            if (normalise(url) == normalise(currentUrl)) return Discovery.Keep
            return Discovery.Adopt(url)
        }

        /**
         * Reads the row and the live socket into what the settings screen may
         * claim. Separate from the publishing so it can be tested: the row names
         * an engine, and only the transport says whether this phone has it.
         */
        fun phaseFor(row: DiscoveryPhase, transport: ConnState): DiscoveryPhase =
            if (row == DiscoveryPhase.CONNECTED && transport != ConnState.ONLINE) {
                DiscoveryPhase.ANNOUNCED
            } else {
                row
            }

        /** A trailing slash is not a different daemon, and would rewrite settings. */
        private fun normalise(url: String): String = url.trim().trimEnd('/')
    }
}

enum class DiscoveryPhase {
    /** No GitHub session yet. */
    NO_TOKEN,

    /** Engine is not reachable at the configured address. */
    IDLE,

    /**
     * The Engine announced its URL, but the transport is not connected.
     */
    ANNOUNCED,

    /** The Engine is reachable and the transport is connected. */
    CONNECTED,

    /** The Engine health endpoint could not be reached. */
    UNREACHABLE,
}

data class DiscoveryState(
    val phase: DiscoveryPhase = DiscoveryPhase.IDLE,
    val tunnelUrl: String = "",
    val version: String = "",
    val online: Boolean = false,
    val error: String = "",
)
