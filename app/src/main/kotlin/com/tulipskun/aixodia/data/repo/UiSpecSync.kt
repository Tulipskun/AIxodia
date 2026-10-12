package com.tulipskun.aixodia.data.repo

import com.tulipskun.aixodia.SettingsStore
import com.tulipskun.aixodia.data.remote.HistoryApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the cached server-driven UI spec current. The daemon is the source of
 * truth for how the app looks and is worded, so this pulls the spec on start and
 * then periodically — a change on the daemon reaches the phone without a
 * rebuild or a reinstall.
 *
 * A failed fetch changes nothing: the last good spec stays cached, so a daemon
 * that is down does not blank the UI.
 */
class UiSpecSync(
    private val settings: SettingsStore,
    private val history: HistoryApi,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            while (true) {
                runCatching { history.uiSpecRaw()?.let { settings.saveUiSpec(it) } }
                delay(POLL_MS)
            }
        }
    }

    private companion object {
        /** One cheap GET; often enough that an edit is picked up while in use. */
        const val POLL_MS = 60_000L
    }
}
