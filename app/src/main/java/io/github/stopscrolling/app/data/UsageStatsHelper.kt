package io.github.stopscrolling.app.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import java.util.Calendar

/** 基于 UsageStatsManager 的每日用量统计 */
object UsageStatsHelper {

    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(), context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun startOfDay(now: Long = System.currentTimeMillis()): Long =
        Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** 今天每个包的使用时长（毫秒），仅包含前台可见时间 */
    fun usageTodayByPackage(context: Context): Map<String, Long> {
        if (!hasUsageAccess(context)) return emptyMap()
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val start = startOfDay()
        val now = System.currentTimeMillis()
        val merged = HashMap<String, Long>()
        usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now)?.forEach { stat ->
            if (stat.lastTimeUsed >= start && stat.totalTimeInForeground > 0) {
                merged[stat.packageName] = (merged[stat.packageName] ?: 0L) + stat.totalTimeInForeground
            }
        }
        return merged
    }

    fun usageOf(context: Context, packageName: String): Long =
        usageTodayByPackage(context)[packageName] ?: 0L

    /** 某应用今天是否已超过限额 */
    fun isOverLimit(context: Context, app: GuardedAppEntity, now: Long = System.currentTimeMillis()): Boolean {
        if (!app.enabled || app.dailyLimitMinutes <= 0) return false
        val used = usageOf(context, app.packageName)
        return used >= app.dailyLimitMinutes * 60_000L
    }

    fun formatDuration(ms: Long): String {
        val minutes = ms / 60_000
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "${h}小时${m}分钟" else "${m}分钟"
    }

    /** 当前是否处于睡觉时段（支持跨零点，如 23 点到 7 点） */
    fun inBedtimeWindow(startHour: Int, endHour: Int, now: Calendar = Calendar.getInstance()): Boolean {
        if (startHour == endHour) return false
        val h = now.get(Calendar.HOUR_OF_DAY)
        return if (startHour < endHour) h in startHour until endHour
        else h >= startHour || h < endHour
    }

    /** 通过用量事件推断最近的前台应用（用于宽限期结束后的复查） */
    fun currentForegroundPackage(context: Context, withinMs: Long = 60_000L): String? {
        if (!hasUsageAccess(context)) return null
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - withinMs, now) ?: return null
        val e = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName
        }
        return last
    }
}
