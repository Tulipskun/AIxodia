package com.tulipskun.aixodia

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.tulipskun.aixodia.data.model.UiSpec
import com.tulipskun.aixodia.ui.LocalUiSpec
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
            // The daemon's UI spec decides the theme and the wording, so it is
            // read here and handed to the whole tree (AX-UI).
            val spec by c.settings.uiSpecFlow.collectAsStateWithLifecycle(initialValue = UiSpec())
            AIxodiaTheme(
                dark = spec.theme.dark,
                cornerRadius = spec.theme.cornerRadius,
                primary = spec.theme.primary,
            ) {
                CompositionLocalProvider(LocalUiSpec provides spec) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        ChatScreen(
                            repo = c.repo, settings = c.settings, history = c.historyApi,
                            socket = c.socket, discovery = c.discovery,
                        )
                    }
                }
            }
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
     * otherwise discovered from the token, and the live socket follows from the
     * daemon address (wss://<host>/ws).
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
