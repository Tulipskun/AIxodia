package com.tulipskun.aixodia

import android.app.Application
import com.tulipskun.aixodia.data.local.AppDatabase
import com.tulipskun.aixodia.data.remote.ChatTransport
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
        container = AppContainer(this)
        // Started here rather than from a screen: the address can change while
        // the app is in the background (a kernel ends, a new one is pushed), and
        // the chat should be current on the next open without anyone asking.
        container.discovery.start()
    }
}

class AppContainer(app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val db: AppDatabase = AppDatabase.get(app)
    val settings = SettingsStore(app)
    val historyApi = HistoryApi(settings)
    // No shared socket: chat is POST + SSE and the terminal is its own
    // WebSocket. Discovery only has to nudge the run stream when the engine
    // address changes.
    val chat: ChatTransport = RunSocket(settings, app)
    val repo = ChatRepository(db, historyApi, chat)
    val discovery = DaemonDiscovery(settings, historyApi, chat, scope)

}
