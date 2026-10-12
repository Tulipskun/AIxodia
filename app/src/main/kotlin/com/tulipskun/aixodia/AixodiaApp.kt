package com.tulipskun.aixodia

import android.app.Application
import com.tulipskun.aixodia.data.local.AppDatabase
import com.tulipskun.aixodia.data.remote.AiDirectSocket
import com.tulipskun.aixodia.data.remote.DaemonDiscovery
import com.tulipskun.aixodia.data.remote.HistoryApi
import com.tulipskun.aixodia.data.repo.ChatRepository
import com.tulipskun.aixodia.data.repo.UiSpecSync
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
        // The daemon owns the app's look and wording (server-driven UI); pull
        // its spec on start so an edit there shows up on the next open.
        container.uiSync.start()
    }
}

class AppContainer(app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val db: AppDatabase = AppDatabase.get(app)
    val settings = SettingsStore(app)
    val historyApi = HistoryApi(settings)
    val socket = AiDirectSocket(settings)
    val repo = ChatRepository(db, historyApi, socket)
    val discovery = DaemonDiscovery(settings, historyApi, socket, scope)
    val uiSync = UiSpecSync(settings, historyApi, scope)
}
