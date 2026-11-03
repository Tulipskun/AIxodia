package com.tulipskun.aixodia.data.remote

import android.util.Log
import com.tulipskun.aixodia.SettingsStore
import com.tulipskun.aixodia.data.model.AiOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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
 * address changes on every redeploy and after every 6-hour kernel cap. It used
 * to be read by hand off the D1 row on a timer; now the daemon pushes the
 * announcement itself over the frame channel, so this listens instead of
 * polling, and the socket already re-reads the stored address on every
 * reconnect, so learning the address and using it is the same step.
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
        // Push, not pull: the daemon announces itself over the frame channel,
        // so discovery listens and never reads the D1 row on a timer.
        scope.launch {
            socket.frames.collect { frame ->
                if (frame.kind == "daemon") handleFrame(frame)
            }
        }
    }

    // One daemon announcement. A live one may adopt a new tunnel and move the
    // socket; an unusable one leaves the address we hold as it is, because a
    // dead address is worse than the one already in hand.
    private suspend fun handleFrame(frame: AiOutput) {
        val current = try {
            settings.current()
        } catch (e: Exception) {
            // DataStore can fail a read while the file is being rewritten. Letting
            // that escape would kill this frame, leaving discovery stuck on the
            // last address it saw.
            Log.w(TAG, "settings read failed: ${e.message}")
            _state.value = DiscoveryState(
                phase = DiscoveryPhase.UNREACHABLE,
                error = e.message?.take(120).orEmpty(),
            )
            return
        }
        if (frame.daemonOnline) {
            if (current.token.isBlank()) {
                // No credential, so no announcement can name a daemon at all.
                _state.value = DiscoveryState(phase = DiscoveryPhase.NO_TOKEN)
                return
            }
            // Only a live daemon counts. The announcement may have been written
            // by a process whose first heartbeat has not landed yet, which would
            // hide the replacement when it comes online, so compare started times.
            val node = NodeInfo(
                tunnelUrl = frame.daemonUrl,
                version = frame.daemonVersion,
                online = true,
                ageS = if (frame.daemonHeartbeat > 0L) System.currentTimeMillis() / 1000L - frame.daemonHeartbeat else -1L,
            )
            val started = frame.daemonStarted
            val replaced = started > 0L && lastStartedAt > 0L && started != lastStartedAt
            if (started > 0L) lastStartedAt = started
            when (val action = decide(current.daemonUrl, node)) {
                is Discovery.Adopt -> {
                    Log.i(TAG, "daemon moved: adopting ${action.url}")
                    settings.saveDaemon(action.url)
                    // The socket is pointed at the old address and would sit there
                    // until its own retry; a redeploy is exactly when it should move
                    // now. The terminal is told too, so an open window follows the
                    // daemon without waiting for its old link to fail first.
                    socket.reconnect()
                    _state.value = DiscoveryState(
                        phase = DiscoveryPhase.CONNECTED,
                        tunnelUrl = action.url,
                        version = node.version,
                        ageS = node.ageS,
                        online = true,
                    )
                }

                Discovery.Keep -> {
                    // Same address, new process: every stream the phone had died
                    // with the old process. Redial as well as the terminal, because
                    // a turn streamed on that socket stops arriving the moment the
                    // engine is replaced, and waiting for the ping to notice costs up
                    // to a ping interval of watching a dead thread. Without the
                    // terminal half the panel sits on a stream the old process took
                    // with it, and tapping that tab does nothing because it still
                    // looks open.
                    if (replaced) {
                        Log.i(TAG, "daemon restarted at $started; redialing the socket and terminal")
                        socket.reconnect()
                    }
                    _state.value = DiscoveryState(
                        phase = DiscoveryPhase.CONNECTED,
                        tunnelUrl = node.tunnelUrl,
                        version = node.version,
                        ageS = node.ageS,
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
        } else {
            _state.value = DiscoveryState(
                phase = DiscoveryPhase.IDLE,
                tunnelUrl = current.daemonUrl,
                online = false,
            )
        }
    }

    companion object {
        /** Reads a push into a decision. Separate from the frame handling so it can be tested. */
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
