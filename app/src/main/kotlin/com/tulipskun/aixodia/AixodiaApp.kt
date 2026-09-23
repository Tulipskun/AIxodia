package com.tulipskun.aixodia

import android.app.Application
import com.tulipskun.aixodia.data.local.AppDatabase
import com.tulipskun.aixodia.data.remote.AiDirectSocket
import com.tulipskun.aixodia.data.remote.HistoryApi
import com.tulipskun.aixodia.data.repo.ChatRepository

class AixodiaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(app: Application) {
    val db: AppDatabase = AppDatabase.get(app)
    val settings = SettingsStore(app)
    val historyApi = HistoryApi(settings)
    val socket = AiDirectSocket(settings)
    val repo = ChatRepository(db, historyApi, socket)
}
