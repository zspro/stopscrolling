package io.github.stopscrolling.app.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * ROM 兼容层：国产 ROM 的电池优化/后台管控会杀掉守护，这里提供
 * 各厂商「自启动管理 / 省电策略」设置页的直达跳转。
 *
 * 跳转目标取自同类商业应用（别刷了 2.1.5）的实测数据：
 * 小米/华为/荣耀/OPPO·一加·realme/vivo·iQOO 全覆盖，
 * 每条链接自带逐级 fallback，最终兜底到应用详情页，保证点击必有响应。
 */
object RomCompat {

    /** 一条可跳转的设置页 */
    data class RomLink(
        val title: String,
        val desc: String,
        val pkg: String? = null,
        val cls: String? = null,
        val action: String? = null,
    )

    data class RomProfile(val romName: String, val links: List<RomLink>)

    fun detect(): String {
        val m = Build.MANUFACTURER.lowercase()
        return when {
            m.contains("xiaomi") || m.contains("redmi") ->
                if (Build.VERSION.SDK_INT >= 30) "澎湃OS/MIUI 12+" else "MIUI"
            m.contains("huawei") -> if (Build.VERSION.SDK_INT >= 29) "HarmonyOS/EMUI" else "EMUI"
            m.contains("honor") -> "MagicOS/荣耀"
            m.contains("oppo") || m.contains("oneplus") || m.contains("realme") -> "ColorOS"
            m.contains("vivo") || m.contains("iqoo") -> "OriginOS/Funtouch"
            m.contains("samsung") -> "One UI"
            m.contains("meizu") -> "Flyme"
            m.contains("lenovo") || m.contains("motorola") -> "联想/moto"
            else -> Build.MANUFACTURER
        }
    }

    /** 当前 ROM 下的推荐跳转列表（从「最该开」到「兜底」排序） */
    fun jumps(): List<RomLink> {
        val m = Build.MANUFACTURER.lowercase()
        return when {
            // 小米：自启动管理 + 省电策略页（组件名实测自 MIUI 12-15 / 澎湃OS）
            m.contains("xiaomi") || m.contains("redmi") -> listOf(
                RomLink(
                    "自启动管理",
                    "允许「放下手机」开机自启并在后台运行",
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                ),
                RomLink(
                    "省电策略",
                    "把本应用的省电策略改为「无限制」",
                    "com.miui.securitycenter",
                    "com.miui.powercenter.legacypowerrank.PowerDetailActivity",
                ),
                RomLink(
                    "神隐模式/其他权限",
                    "检查后台定位与后台弹窗等特殊权限",
                    "com.miui.securitycenter",
                    "com.miui.permcenter.settings.OtherPermissionsActivity"
                ),
                batteryOptimizationLink(),
                appDetailsLink()
            )
            // 华为 EMUI/HarmonyOS
            m.contains("huawei") -> listOf(
                RomLink(
                    "启动管理",
                    "关闭「自动管理」，手动允许自启动/关联启动/后台活动",
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                ),
                RomLink(
                    "省电管理",
                    "把本应用加入电池优化白名单",
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.power.ui.HwPowerManagerActivity"
                ),
                RomLink(
                    "手机管家主页",
                    "在管家中手动放行（若上面的页面打不开）",
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.mainscreen.MainScreenActivity"
                ),
                batteryOptimizationLink(),
                appDetailsLink()
            )
            // 荣耀 MagicOS（组件与华为系同构但独立包名）
            m.contains("honor") -> listOf(
                RomLink(
                    "自启动管理",
                    "在启动管理中允许后台活动",
                    "com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                ),
                RomLink(
                    "手机管家主页",
                    "在管家中手动放行",
                    "com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.mainscreen.MainScreenActivity"
                ),
                batteryOptimizationLink(),
                appDetailsLink()
            )
            // OPPO / 一加 / realme（ColorOS 系）
            m.contains("oppo") || m.contains("oneplus") || m.contains("realme") -> listOf(
                RomLink(
                    "自启动管理",
                    "允许「放下手机」自启动与后台运行",
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                ),
                RomLink(
                    "耗电管理",
                    "关闭本应用的智能耗电保护/允许后台运行",
                    "com.oplus.battery",
                    "com.oplus.powermanager.fuelgaue.PowerConsumptionActivity"
                ),
                RomLink(
                    "耗电详情（旧版）",
                    "ColorOS 旧版本的省电设置页",
                    "com.coloros.oppoguardelf",
                    "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"
                ),
                batteryOptimizationLink(),
                appDetailsLink()
            )
            // vivo / iQOO（OriginOS/Funtouch 系）
            m.contains("vivo") || m.contains("iqoo") -> listOf(
                RomLink(
                    "后台自启动",
                    "在权限管理中允许自启动",
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                ),
                RomLink(
                    "后台高耗电",
                    "允许本应用后台高耗电（否则守护会被冻结）",
                    "com.iqoo.powersaving",
                    "com.iqoo.powersaving.BackgroundHighUsageActivity"
                ),
                RomLink(
                    "权限管理",
                    "检查悬浮窗等特殊权限",
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.PurviewTabActivity"
                ),
                batteryOptimizationLink(),
                appDetailsLink()
            )
            // 通用 ROM
            else -> listOf(
                batteryOptimizationLink(),
                RomLink(
                    "无障碍设置",
                    "确认「守门员」服务处于开启状态",
                    action = Settings.ACTION_ACCESSIBILITY_SETTINGS
                ),
                appDetailsLink()
            )
        }
    }

    private fun batteryOptimizationLink() = RomLink(
        "电池优化白名单",
        "请求系统忽略本应用的电池优化",
        action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
    )

    private fun appDetailsLink() = RomLink(
        "应用详情页",
        "兜底入口：在系统设置里找到「放下手机」手动放行",
        action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
    )

    /**
     * 尝试按顺序打开：专属组件 → action 页 → 应用详情页。
     * 每级失败自动降级，返回是否成功打开了任意页面。
     */
    fun launch(context: Context, link: RomLink): Boolean {
        val pm = context.packageManager
        val candidates = buildList {
            // 1) 厂商专属组件页
            if (link.pkg != null && link.cls != null) {
                add(Intent().setComponent(ComponentName(link.pkg, link.cls)))
            }
            // 2) 声明的 action
            if (!link.action.isNullOrBlank()) {
                val i = Intent(link.action)
                if (link.action == Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) {
                    i.data = Uri.parse("package:${context.packageName}")
                } else if (link.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
                    i.data = Uri.fromParts("package", context.packageName, null)
                }
                add(i)
            }
            // 3) 兜底：应用详情页
            add(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", context.packageName, null))
            )
        }
        for (intent in candidates) {
            try {
                if (intent.component == null && pm.resolveActivity(intent, 0) == null) continue
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (_: Exception) {
                // 尝试下一级
            }
        }
        return false
    }
}
