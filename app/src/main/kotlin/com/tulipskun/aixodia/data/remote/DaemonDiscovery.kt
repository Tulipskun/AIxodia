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
 * needed to read the `tunnel` row, and the socket already re-reads the stored
 * address on every reconnect, so learning the address and using it is the same
 * step.
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

    /** How often to read the row. The daemon beats every 30s, so faster is waste. */
    private val pollMs = 30_000L

    /**
     * The engine instance whose address was last read. A different started
     * means the daemon was replaced: every relay stream the phone had —
     * terminals included — died with the old process, even though the address
     * did not change and the relay may never have shown the engine as absent.
     */
    private var lastStartedAt = 0L

    fun start() {
        scope.launch { loop() }
    }

    private suspend fun loop() {
        var backoff = pollMs
        while (scope.isActive) {
            val current = try {
                settings.current()
            } catch (e: Exception) {
                // DataStore can fail a read while the file is being rewritten.
                // Letting that escape would kill this loop for the rest of the
                // process, leaving discovery stuck on the last address it saw.
                Log.w(TAG, "settings read failed: ${e.message}")
                _state.value = DiscoveryState(
                    phase = DiscoveryPhase.UNREACHABLE,
                    error = e.message?.take(120).orEmpty(),
                )
                delay(pollMs)
                continue
            }
            if (current.token.isBlank()) {
                // No credential, so the row cannot be read at all. Not an error
                // worth retrying quickly; the token arrives in settings.
                _state.value = DiscoveryState(phase = DiscoveryPhase.NO_TOKEN)
                delay(pollMs)
                continue
            }
            try {
                val node = history.node()
                // Only a live daemon counts. The row may be rewritten by a
                // process whose first heartbeat has not landed yet; recording
                // that started early would hide the replacement when it comes
                // online a poll later.
                val started = if (node?.online == true) node.startedAt else 0L
                val replaced = started > 0L && lastStartedAt > 0L && started != lastStartedAt
                if (started > 0L) lastStartedAt = started
                when (val action = decide(current.daemonUrl, node)) {
                    is Discovery.Adopt -> {
                        Log.i(TAG, "daemon moved: adopting ${action.url}")
                        settings.saveDaemon(action.url)
                        // The socket is pointed at the old address and would sit
                        // there until its own retry; a redeploy is exactly when
                        // it should move now. The terminal is told too, so an
                        // open window follows the daemon without waiting for its
                        // old link to fail first.
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
                        // Same address, new process: every stream the phone had
                        // died with the old process. The socket is redialed as
                        // well as the terminal, because a turn streamed on that
                        // socket stops arriving the moment the engine is
                        // replaced, and waiting for the ping to notice costs up
                        // to a ping interval of watching a dead thread. Without
                        // the terminal half the panel sits on a stream the old
                        // process took with it, and tapping that tab does
                        // nothing because it still looks open.
                        if (replaced) {
                            Log.i(TAG, "daemon restarted at $started; redialing the socket and terminal")
                            socket.reconnect()
                        }
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
                        backoff = pollMs
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
                // Only a failed read backs off. Letting the doubling run on
                // success stretched the poll from 30 seconds to five minutes,
                // so a replaced engine went unnoticed long after it was back.
                backoff = min(backoff * 2, 5 * 60_000L)
            }
            delay(backoff + Random.nextLong(0, 2_000))
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
