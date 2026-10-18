package io.github.stopscrolling.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import io.github.stopscrolling.app.util.Notifications
import io.github.stopscrolling.app.util.PermissionChecker

/**
 * 守护自愈：国产 ROM 杀后台后无障碍服务静默死亡，用户毫无感知，拦截就失效了。
 * 借鉴同类应用的「worker 周期自检」方案：AlarmManager 周期唤醒，
 * 检查无障碍服务是否存活；断了就发高优通知，点击直达无障碍设置页。
 *
 * 使用 setInexactRepeating，无需 SCHEDULE_EXACT_ALARM 权限。
 */
class GuardWatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SELF_CHECK) return
        schedule(context) // 前滚下一次检查

        val accessibilityOk = PermissionChecker.accessibilityServiceEnabled(context)
        val alive = GuardAccessibilityService.isRunning()
        if (accessibilityOk && alive) return

        // 服务已被系统杀掉或被用户关闭：发提醒（通知有 setAutoCancel，重复通知会覆盖同 ID，不轰炸）
        Notifications.notifyGuardDown(context)
    }

    companion object {
        private const val ACTION_SELF_CHECK = "io.github.stopscrolling.GUARD_SELF_CHECK"
        private const val REQUEST_CODE = 2001

        /** 每 15 分钟自检一次 */
        private const val INTERVAL_MS = 15 * 60_000L

        /** 开机/应用启动后调用，启动周期自检 */
        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, GuardWatchdogReceiver::class.java).setAction(ACTION_SELF_CHECK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + INTERVAL_MS,
                INTERVAL_MS,
                pi
            )
        }

        fun cancel(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                Intent(context, GuardWatchdogReceiver::class.java).setAction(ACTION_SELF_CHECK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
        }
    }
}
