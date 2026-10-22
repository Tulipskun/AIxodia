package com.tulipskun.aixodia

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.lifecycle.lifecycleScope
import com.tulipskun.aixodia.ui.chat.ChatScreen
import com.tulipskun.aixodia.ui.theme.AIxodiaTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** When the activity left the foreground; 0 before the first pause. */
    private var backgroundedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge: let Compose handle status/nav bars via WindowInsets.
        // Without this, the system draws bars over the app (content under NavBar).
        enableEdgeToEdge()
        val c = (application as AixodiaApp).container
        applyDebugSeed(intent)
        setContent {
            AIxodiaTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    ChatScreen(
                        repo = c.repo, settings = c.settings, history = c.historyApi,
                        socket = c.chat, discovery = c.discovery,
                    )
                }
            }
        }
    }

    /**
     * A stream that died while the app was in the background must not have to
     * wait for a timeout to be noticed: coming back to the foreground is
     * exactly when the connection is likely stale, so an offline one is
     * re-established at once.
     */
    override fun onPause() {
        backgroundedAt = SystemClock.elapsedRealtime()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        val c = (application as AixodiaApp).container
        val away = if (backgroundedAt > 0L) {
            SystemClock.elapsedRealtime() - backgroundedAt
        } else {
            0L
        }
        backgroundedAt = 0L
        // The SSE stream is not replayed by a connection Android froze while
        // the app was away, and a long background trip may also mean the
        // daemon was replaced. Reconnect deliberately; the transport resumes at
        // its last sequence number.
        if (c.chat.state.value != com.tulipskun.aixodia.data.remote.ConnState.ONLINE || away >= 30_000L) {
            c.chat.reconnect()
        }
    }

    /**
     * Debug-only seeding so a device (or emulator) can be configured for a test
     * without hand-typing on a phone:
     *
     *   adb shell am start -n com.tulipskun.aixodia/.MainActivity \
     *     --es aixodia.token <Cloudflare API token> \
     *     --es aixodia.address https://example.trycloudflare.com \
     *     --es aixodia.session work-1
     *
     * `aixodia.account` and `aixodia.database` override the ids that are
     * otherwise discovered from the token, and the run stream follows from the
     * daemon address.
     *
     * It is compiled out of release builds: BuildConfig.DEBUG is false there,
     * so a shipped APK has no way to receive connection values this way, and
     * nothing is ever baked into the binary.
     */
    private fun applyDebugSeed(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        val address = intent.getStringExtra("aixodia.address").orEmpty()
        val token = intent.getStringExtra("aixodia.token").orEmpty()
        val account = intent.getStringExtra("aixodia.account").orEmpty()
        val database = intent.getStringExtra("aixodia.database").orEmpty()
        val session = intent.getStringExtra("aixodia.session").orEmpty()
        if (address.isBlank() && token.isBlank()) return
        val settings = (application as AixodiaApp).container.settings
        lifecycleScope.launch {
            // One credential the app reads D1 with; the daemon address is only
            // needed for the live socket and the provider/model API.
            if (token.isNotBlank()) settings.saveD1(token, account, database)
            if (address.isNotBlank()) settings.saveDaemon(address)
            if (session.isNotBlank()) settings.saveSession(session)
            // Clear the extras so a configuration change does not re-apply them.
            intent.removeExtra("aixodia.address")
            intent.removeExtra("aixodia.token")
            intent.removeExtra("aixodia.account")
            intent.removeExtra("aixodia.database")
            intent.removeExtra("aixodia.session")
        }
    }
}
