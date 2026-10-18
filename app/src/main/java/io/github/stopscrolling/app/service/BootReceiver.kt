package io.github.stopscrolling.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机后无障碍服务会由系统自动恢复；此处负责重启守护自检闹钟 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // 无障碍服务由系统在开机后自动绑定。
            // 重新排期守护自检：定期确认服务存活，断线时发高优通知。
            GuardWatchdogReceiver.schedule(context)
        }
    }
}
