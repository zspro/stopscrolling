package io.github.stopscrolling.app

import android.app.Application
import io.github.stopscrolling.app.data.AppDatabase
import io.github.stopscrolling.app.data.SettingsStore
import io.github.stopscrolling.app.service.GuardWatchdogReceiver
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
        // 守护自愈：应用进程起来就排期周期自检（开机后由 BootReceiver 再排一次）
        GuardWatchdogReceiver.schedule(this)
    }
}
