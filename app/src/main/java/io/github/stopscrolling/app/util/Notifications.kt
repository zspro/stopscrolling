package io.github.stopscrolling.app.util

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.stopscrolling.app.R

/** 通知管理：专注倒计时 + 限额预警 */
object Notifications {

    const val CHANNEL_FOCUS = "focus"
    const val CHANNEL_WARN = "warning"
    private const val ID_FOCUS = 1001

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_FOCUS, "专注模式", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "专注进行时的倒计时通知" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_WARN, "限额预警", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "应用用量接近每日限额时的提醒" }
        )
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** 专注倒计时通知：使用 chronometer 倒计时，无需轮询更新 */
    fun notifyFocus(context: Context, endAt: Long, remainText: String) {
        if (!canNotify(context)) return
        val notification: Notification =
            Notification.Builder(context, CHANNEL_FOCUS)
                .setSmallIcon(R.drawable.ic_stat_guard)
                .setContentTitle(context.getString(R.string.notification_focus_title))
                .setContentText(remainText)
                .setWhen(endAt)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        nm(context).notify(ID_FOCUS, notification)
    }

    fun cancelFocus(context: Context) {
        nm(context).cancel(ID_FOCUS)
    }

    /** 用量到达 80% 时的软预警 */
    fun notifyWarning(context: Context, appName: String, limitMinutes: Int) {
        if (!canNotify(context)) return
        val text = "「$appName」今日限额（$limitMinutes 分钟）快用完了，注意节制哦"
        val notification: Notification =
            Notification.Builder(context, CHANNEL_WARN)
                .setSmallIcon(R.drawable.ic_stat_guard)
                .setContentTitle("限额预警")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
        nm(context).notify(appName.hashCode(), notification)
    }

    private fun nm(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
