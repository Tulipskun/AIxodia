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
 * The Named Tunnel hostname is stable across engine restarts. Discovery reads
 * its configured endpoint from KV; the live socket, not a heartbeat, determines
 * whether the engine is currently reachable.
 */
sealed interface Discovery {
    /** The row says nothing usable. Keep whatever address we have. */
    data object Wait : Discovery

    /** The row names the address already in use. Nothing to write. */
    data object Keep : Discovery

    /** A different configured daemon endpoint was found. Use its URL. */
    data class Adopt(val url: String) : Discovery
}

/**
 * Keeps the daemon address pointing at whatever kernel is currently serving.
 *
 * The daemon keeps a stable Named Tunnel URL in Workers KV. Discovery polls
 * that value so cold app starts work even when the engine's one-time push was
 * sent before the phone connected; live daemon frames still update it promptly.
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
     * The engine instance whose address was last read. A different started
     * time means the daemon was replaced: every terminal stream the phone had
     * died with the old process, even though the address did not change and
     * discovery may not have noticed the absence.
     */
    private var lastStartedAt = 0L

    fun start() {
        scope.launch {
            socket.frames.collect { frame ->
                if (frame.kind == "daemon") handleFrame(frame)
            }
        }
        scope.launch {
            while (isActive) {
                try {
                    handleNode(history.node())
                } catch (e: Exception) {
                    Log.w(TAG, "Workers KV discovery failed: ${e.message}")
                    _state.value = DiscoveryState(
                        phase = DiscoveryPhase.UNREACHABLE,
                        error = e.message?.take(120).orEmpty(),
                    )
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
            _state.value = DiscoveryState(
                phase = DiscoveryPhase.UNREACHABLE,
                error = e.message?.take(120).orEmpty(),
            )
            return
        }
        if (current.token.isBlank()) {
            _state.value = DiscoveryState(phase = DiscoveryPhase.NO_TOKEN)
            return
        }
        if (node == null) {
            _state.value = DiscoveryState(
                phase = DiscoveryPhase.IDLE,
                tunnelUrl = current.daemonUrl,
                online = false,
            )
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
                _state.value = DiscoveryState(
                    phase = DiscoveryPhase.CONNECTED,
                    tunnelUrl = action.url,
                    version = node.version,
                    online = true,
                )
            }

            Discovery.Keep -> {
                if (replaced) {
                    Log.i(TAG, "engine restarted at $started; redialing socket and terminal")
                    socket.reconnect()
                }
                _state.value = DiscoveryState(
                    phase = DiscoveryPhase.CONNECTED,
                    tunnelUrl = node.tunnelUrl,
                    version = node.version,
                    online = true,
                )
            }

            Discovery.Wait -> {
                _state.value = DiscoveryState(
                    phase = DiscoveryPhase.IDLE,
                    tunnelUrl = current.daemonUrl,
                    online = false,
                )
            }
        }
    }

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

        /** A trailing slash is not a different daemon, and would rewrite settings. */
        private fun normalise(url: String): String = url.trim().trimEnd('/')
    }
}

enum class DiscoveryPhase {
    /** No token yet, so the row cannot be read. */
    NO_TOKEN,

    /** No endpoint is configured in KV. The stored address is left as it is. */
    IDLE,

    /** A live daemon is known and its address is the stored one. */
    CONNECTED,

    /** The Workers KV namespace could not be read. */
    UNREACHABLE,
}

data class DiscoveryState(
    val phase: DiscoveryPhase = DiscoveryPhase.IDLE,
    val tunnelUrl: String = "",
    val version: String = "",
    val ageS: Long = -1,
    val online: Boolean = false,
    val error: String = "",
)
