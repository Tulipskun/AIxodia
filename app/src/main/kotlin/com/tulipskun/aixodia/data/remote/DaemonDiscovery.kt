package com.tulipskun.aixodia.data.remote

import android.util.Log
import com.tulipskun.aixodia.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.random.Random

private const val TAG = "DaemonDiscovery"

/**
 * What discovery decided to do about the address it just read.
 *
 * Switching on a stale heartbeat is the failure worth avoiding: the URL is
 * random per boot, so the row briefly names a daemon that has already stopped.
 * Adopting that would replace a working address with a dead one, and the phone
 * would go offline because a kernel ended on its own.
 */
sealed interface Discovery {
    /** The row says nothing usable. Keep whatever address we have. */
    data object Wait : Discovery

    /** The row names the address already in use. Nothing to write. */
    data object Keep : Discovery

    /** A different, live daemon is serving. Use its URL. */
    data class Adopt(val url: String) : Discovery
}

/**
 * Keeps the daemon address pointing at whatever kernel is currently serving.
 *
 * The daemon announces a random quick-tunnel URL every 30 seconds, so the
 * address changes on every redeploy and after every 6-hour kernel cap. Reading
 * it by hand is what this removes: the app already holds the one credential
 * needed to read the `nodes` row, and the socket already re-reads the stored
 * address on every reconnect, so learning the address and using it is the same
 * step.
 *
 * Deliberately not a background service: it runs for as long as the app does,
 * which is the only time a phone can be talked to anyway.
 */
class DaemonDiscovery(
    private val settings: SettingsStore,
    private val history: HistoryApi,
    private val socket: AiDirectSocket,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(DiscoveryState())

    /** What discovery last saw, for the settings screen to show. */
    val state: StateFlow<DiscoveryState> = _state

    /** How often to read the row. The daemon beats every 30s, so faster is waste. */
    private val pollMs = 30_000L

    fun start() {
        scope.launch { loop() }
    }

    private suspend fun loop() {
        var backoff = pollMs
        while (scope.isActive) {
            val current = settings.current()
            if (current.token.isBlank()) {
                // No credential, so the row cannot be read at all. Not an error
                // worth retrying quickly; the token arrives in settings.
                _state.value = DiscoveryState(phase = DiscoveryPhase.NO_TOKEN)
                delay(pollMs)
                continue
            }
            try {
                val node = history.node()
                when (val action = decide(current.daemonUrl, node)) {
                    is Discovery.Adopt -> {
                        Log.i(TAG, "daemon moved: adopting ${action.url}")
                        settings.saveDaemon(action.url)
                        // The socket is pointed at the old address and would sit
                        // there until its own retry; a redeploy is exactly when
                        // it should move now.
                        socket.reconnect()
                        _state.value = DiscoveryState(
                            phase = DiscoveryPhase.CONNECTED,
                            tunnelUrl = action.url,
                            version = node?.version.orEmpty(),
                            ageS = node?.ageS ?: -1,
                            online = node?.online == true,
                        )
                        backoff = pollMs
                    }

                    Discovery.Keep -> {
                        _state.value = DiscoveryState(
                            phase = DiscoveryPhase.CONNECTED,
                            tunnelUrl = node?.tunnelUrl.orEmpty().ifBlank { current.daemonUrl },
                            version = node?.version.orEmpty(),
                            ageS = node?.ageS ?: -1,
                            online = node?.online == true,
                        )
                        backoff = pollMs
                    }

                    Discovery.Wait -> {
                        _state.value = DiscoveryState(
                            phase = DiscoveryPhase.IDLE,
                            tunnelUrl = current.daemonUrl,
                            online = false,
                        )
                    }
                }
            } catch (e: Exception) {
                // A D1 hiccup must not stop the loop, and must not throw away the
                // address we already have.
                Log.w(TAG, "discovery read failed: ${e.message}")
                _state.value = DiscoveryState(
                    phase = DiscoveryPhase.UNREACHABLE,
                    tunnelUrl = current.daemonUrl,
                    error = e.message?.take(120).orEmpty(),
                )
            }
            delay(backoff + Random.nextLong(0, 2_000))
            backoff = min(backoff * 2, 5 * 60_000L)
        }
    }

    companion object {
        /** Reads a row into a decision. Separate from the loop so it can be tested. */
        fun decide(currentUrl: String, node: NodeInfo?): Discovery {
            val url = node?.tunnelUrl?.trim().orEmpty()
            // Nothing announced yet, or the row is empty.
            if (url.isEmpty()) return Discovery.Wait
            // The daemon that wrote this has stopped beating. A dead URL is worse
            // than the address already in hand, so leave it alone.
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

    /** The row has no live daemon. The stored address is left as it is. */
    IDLE,

    /** A live daemon is known and its address is the stored one. */
    CONNECTED,

    /** The row could not be read at all. */
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
