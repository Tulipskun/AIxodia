package com.tulipskun.aixodia

import android.app.Application
import com.tulipskun.aixodia.data.local.AppDatabase
import com.tulipskun.aixodia.data.remote.ChatTransport
import com.tulipskun.aixodia.data.remote.ClientToken
import com.tulipskun.aixodia.data.remote.DaemonDiscovery
import com.tulipskun.aixodia.data.remote.HistoryApi
import com.tulipskun.aixodia.data.remote.RunSocket
import com.tulipskun.aixodia.data.repo.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AixodiaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        container = AppContainer(this)
        // Observe the Engine's announced URL and health state while the app is
        // running. The default Named Tunnel URL is already configured locally;
        // discovery never reads Cloudflare KV or requires a Cloudflare token.
        container.discovery.start()
    }
}

class AppContainer(app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val db: AppDatabase = AppDatabase.get(app)
    val settings = SettingsStore(app)
    // One app-wide provider: API calls pass the already-issued Engine session.
    // GitHub OAuth is used only during the explicit sign-in exchange.
    val clientToken: ClientToken = ClientToken.shared
    val historyApi = HistoryApi(settings, clientToken = clientToken)
    // No shared socket: chat is POST + SSE and the terminal is its own
    // WebSocket. Discovery only has to nudge the run stream when the engine
    // address changes — and the run stream reads the announced address itself
    // when a dial fails, since the announcements travel on that dead socket.
    val chat: ChatTransport = RunSocket(settings, historyApi, app, clientToken)
    val repo = ChatRepository(db, historyApi, chat)
    val discovery = DaemonDiscovery(settings, historyApi, chat, scope)

}
