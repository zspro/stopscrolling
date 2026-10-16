package io.github.stopscrolling.app

import android.app.Application
import io.github.stopscrolling.app.data.AppDatabase
import io.github.stopscrolling.app.data.SettingsStore
import io.github.stopscrolling.app.util.Notifications

class StopScrollApp : Application() {
    lateinit var database: AppDatabase
        private set
    lateinit var settings: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.get(this)
        settings = SettingsStore(this)
        Notifications.ensureChannels(this)
    }
}
