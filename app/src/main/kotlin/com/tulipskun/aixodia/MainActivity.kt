package com.tulipskun.aixodia

import android.content.Intent
import android.os.Bundle
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
                    ChatScreen(repo = c.repo, settings = c.settings, history = c.historyApi, socket = c.socket)
                }
            }
        }
    }

    /**
     * Debug-only seeding so a device (or emulator) can be configured for a test
     * without hand-typing on a phone:
     *
     *   adb shell am start -n com.tulipskun.aixodia/.MainActivity \
     *     --es aixodia.worker https://example.workers.dev \
     *     --es aixodia.ws wss://example.trycloudflare.com/ws \
     *     --es aixodia.token <D1 token> \
     *     --es aixodia.session work-1
     *
     * It is compiled out of release builds: BuildConfig.DEBUG is false there,
     * so a shipped APK has no way to receive connection values this way, and
     * nothing is ever baked into the binary.
     */
    private fun applyDebugSeed(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        val worker = intent.getStringExtra("aixodia.worker").orEmpty()
        val ws = intent.getStringExtra("aixodia.ws").orEmpty()
        val token = intent.getStringExtra("aixodia.token").orEmpty()
        val session = intent.getStringExtra("aixodia.session").orEmpty()
        if (worker.isBlank() && ws.isBlank() && token.isBlank()) return
        val settings = (application as AixodiaApp).container.settings
        lifecycleScope.launch {
            settings.saveConnection(ws, worker, token)
            if (session.isNotBlank()) settings.saveSession(session)
            // Clear the extras so a configuration change does not re-apply them.
            intent.removeExtra("aixodia.worker")
            intent.removeExtra("aixodia.ws")
            intent.removeExtra("aixodia.token")
            intent.removeExtra("aixodia.session")
        }
    }
}
