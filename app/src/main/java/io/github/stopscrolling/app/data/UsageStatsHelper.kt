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

    /**
     * 今天每个包的使用时长（毫秒），仅前台可见时间。
     *
     * 用事件（RESUMED/PAUSED/STOPPED）从今天零点累加，而不是 queryUsageStats：
     * 后者的日桶按系统统计周期对齐、不按本地零点对齐，会把昨天零点后的时间算进“今天”，
     * 导致限额被提前触发、今日数字偏大。
     */
    fun usageTodayByPackage(context: Context): Map<String, Long> {
        if (!hasUsageAccess(context)) return emptyMap()
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val start = startOfDay()
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(start, now) ?: return emptyMap()
        val merged = HashMap<String, Long>()
        val resumedAt = HashMap<String, Long>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> resumedAt[pkg] = e.timeStamp
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val t0 = resumedAt.remove(pkg) ?: continue
                    val delta = e.timeStamp - t0
                    if (delta > 0) merged[pkg] = (merged[pkg] ?: 0L) + delta
                }
            }
        }
        // 仍在前台（有 RESUMED 无配对 PAUSED）的应用，用当前时间结算
        for ((pkg, t0) in resumedAt) {
            val delta = now - t0
            if (delta > 0) merged[pkg] = (merged[pkg] ?: 0L) + delta
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
