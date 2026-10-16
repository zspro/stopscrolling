package io.github.stopscrolling.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机后无障碍服务会由系统自动恢复；此处用于刷新专注状态等 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // 无障碍服务由系统在开机后自动绑定，无需额外处理。
            // 预留：可以在这里重新校验专注模式结束时间。
        }
    }
}
