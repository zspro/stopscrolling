package io.github.stopscrolling.app.util

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import io.github.stopscrolling.app.service.GuardAccessibilityService
import io.github.stopscrolling.app.service.GuardDeviceAdminReceiver

/** 权限缺失情况统一检查：首页横幅与设置页共用 */
object PermissionChecker {

    data class PermGap(val id: String, val label: String)

    fun missingGaps(context: Context, tick: Int = 0): List<PermGap> {
        val gaps = mutableListOf<PermGap>()
        if (!hasUsageAccess(context)) gaps += PermGap("usage", "使用情况访问权限")
        if (!GuardAccessibilityService.isRunning()) gaps += PermGap("accessibility", "无障碍服务")
        if (!Settings.canDrawOverlays(context)) gaps += PermGap("overlay", "悬浮窗权限")
        if (!isAdminActive(context)) gaps += PermGap("admin", "设备管理员")
        if (!isIgnoringBatteryOptimizations(context)) gaps += PermGap("battery", "电池优化白名单")
        return gaps
    }

    fun hasUsageAccess(context: Context): Boolean =
        io.github.stopscrolling.app.data.UsageStatsHelper.hasUsageAccess(context)

    fun isAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val admin = ComponentName(context, GuardDeviceAdminReceiver::class.java)
        return dpm?.isAdminActive(admin) == true
    }

    /**
     * 无障碍服务是否已启用：优先看服务是否已连接，
     * 再看系统设置里的启用列表（服务连接稍有延迟，用设置兜底避免引导流程误判）。
     */
    fun accessibilityServiceEnabled(context: Context): Boolean {
        if (GuardAccessibilityService.isRunning()) return true
        val setting = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return setting.split(':').any { it.startsWith(context.packageName) }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isIgnoringBatteryOptimizations(context.packageName) == true
    }
}
