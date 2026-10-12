package com.tulipskun.aixodia.data.repo

import com.tulipskun.aixodia.SettingsStore
import com.tulipskun.aixodia.data.remote.AiDirectSocket
import com.tulipskun.aixodia.data.remote.HistoryApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Keeps the cached server-driven UI spec current — without polling.
 *
 * The daemon is the source of truth for how the app looks and is worded, and it
 * pushes the spec over the WebSocket the app already holds: once when the
 * socket connects and again the moment it changes. So the only fetch here is a
 * single one at startup, which covers a first launch before the socket is up
 * and a phone whose socket never connects. After that the push does the work,
 * and a change on the daemon reaches an open app immediately.
 *
 * A failed fetch or a dropped push changes nothing: the last good spec stays
 * cached, so a daemon that is down does not blank the UI.
 */
class UiSpecSync(
    private val settings: SettingsStore,
    private val history: HistoryApi,
    private val socket: AiDirectSocket,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            runCatching { history.uiSpecRaw()?.let { settings.saveUiSpec(it) } }
        }
        scope.launch {
            socket.uiSpecs.collect { settings.saveUiSpec(it) }
        }
    }
}
