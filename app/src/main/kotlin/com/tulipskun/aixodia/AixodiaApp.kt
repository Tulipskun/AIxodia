package com.tulipskun.aixodia

import android.app.Application
import com.tulipskun.aixodia.data.local.AppDatabase
import com.tulipskun.aixodia.data.remote.AiDirectSocket
import com.tulipskun.aixodia.data.remote.DaemonDiscovery
import com.tulipskun.aixodia.data.remote.HistoryApi
import com.tulipskun.aixodia.data.remote.RelayClient
import com.tulipskun.aixodia.data.remote.TerminalSocket
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
        // The socket is opened here too, not by a screen: the address only
        // exists after discovery has read it, and the relay waits for that
        // address by itself. A phone that only opens the settings screen would
        // otherwise never dial at all.
        container.relay.connect()
    }
}

class AppContainer(app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val db: AppDatabase = AppDatabase.get(app)
    val settings = SettingsStore(app)
    // One socket for the whole app: chat, terminal and the HTTP API all ride it.
    val relay = RelayClient(settings)
    val historyApi = HistoryApi(settings, relay)
    val socket = AiDirectSocket(settings, relay)
    val terminal = TerminalSocket(settings, relay)
    val repo = ChatRepository(db, historyApi, socket)
    val discovery = DaemonDiscovery(settings, historyApi, socket, terminal, scope)
}
