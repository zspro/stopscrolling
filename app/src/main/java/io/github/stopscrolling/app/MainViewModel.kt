package io.github.stopscrolling.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.stopscrolling.app.data.AppDatabase
import io.github.stopscrolling.app.data.AppInfoHelper
import io.github.stopscrolling.app.data.GuardedAppEntity
import io.github.stopscrolling.app.data.InstalledApp
import io.github.stopscrolling.app.data.SettingsStore
import io.github.stopscrolling.app.data.UsageStatsHelper
import io.github.stopscrolling.app.service.GuardAccessibilityService
import io.github.stopscrolling.app.util.Notifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TopAppUsage(val label: String, val minutes: Long)

data class HomeState(
    val usageByPackage: Map<String, Long> = emptyMap(),
    val topApps: List<TopAppUsage> = emptyList(),
    val blockedToday: Int = 0,
    val focusEndAt: Long = 0L,
    /** 睡觉时段状态：null = 未启用，否则显示描述文本 */
    val bedtimeText: String? = null,
    val bedtimeActive: Boolean = false,
    val bedtimeEnabled: Boolean = false,
    val bedtimeStartHour: Int = 23,
    val bedtimeEndHour: Int = 7
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)
    private val settings = SettingsStore(app)

    private val _home = MutableStateFlow(HomeState())
    val home: StateFlow<HomeState> = _home

    val guardedApps: StateFlow<List<GuardedAppEntity>> =
        db.guardedAppDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        refreshUsage()
        viewModelScope.launch {
            combine(
                settings.focusEndAt,
                settings.bedtimeEnabled,
                settings.bedtimeStartHour,
                settings.bedtimeEndHour
            ) { focusEnd, enabled, start, end ->
                val active = enabled && UsageStatsHelper.inBedtimeWindow(start, end)
                val text = if (!enabled) null else
                    "睡觉时段已开启（%02d:00 - %02d:00）".format(start, end) +
                        if (active) " · 进行中" else ""
                _home.value.copy(
                    focusEndAt = focusEnd,
                    bedtimeText = text,
                    bedtimeActive = active,
                    bedtimeEnabled = enabled,
                    bedtimeStartHour = start,
                    bedtimeEndHour = end
                )
            }.collect { _home.value = it }
        }
        viewModelScope.launch {
            val since = UsageStatsHelper.startOfDay()
            db.blockEventDao().observeCountSince("BLOCKED", since).collect { count ->
                _home.value = _home.value.copy(blockedToday = count)
            }
        }
    }

    fun refreshUsage() {
        viewModelScope.launch {
            val usage = UsageStatsHelper.usageTodayByPackage(getApplication())
            val top = usage.entries
                .filter { it.key != getApplication<Application>().packageName }
                .sortedByDescending { it.value }
                .take(5)
                .map { TopAppUsage(AppInfoHelper.appLabel(getApplication(), it.key), it.value / 60_000) }
            _home.value = _home.value.copy(usageByPackage = usage, topApps = top)
        }
    }

    fun addGuardedApp(label: String, pkg: String, limitMinutes: Int) {
        viewModelScope.launch {
            db.guardedAppDao().upsert(
                GuardedAppEntity(
                    packageName = pkg,
                    appName = label,
                    dailyLimitMinutes = limitMinutes,
                    enabled = true
                )
            )
        }
    }

    fun updateLimit(app: GuardedAppEntity, limitMinutes: Int) {
        viewModelScope.launch {
            db.guardedAppDao().upsert(app.copy(dailyLimitMinutes = limitMinutes))
        }
    }

    fun toggleEnabled(app: GuardedAppEntity) {
        viewModelScope.launch {
            db.guardedAppDao().upsert(app.copy(enabled = !app.enabled))
        }
    }

    fun removeGuardedApp(app: GuardedAppEntity) {
        viewModelScope.launch {
            db.guardedAppDao().delete(app.packageName)
        }
    }

    fun quickAddShortVideoApps() {
        viewModelScope.launch {
            val candidates = mapOf(
                "com.ss.android.ugc.aweme" to "抖音",
                "com.smile.gifmaker" to "快手",
                "com.xingin.xhs" to "小红书",
                "com.tencent.mm" to "微信（视频号）",
                "tv.danmaku.bili" to "哔哩哔哩"
            )
            val pm = getApplication<Application>().packageManager
            candidates.forEach { (pkg, label) ->
                val installed = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull() != null
                if (installed && db.guardedAppDao().find(pkg) == null) {
                    db.guardedAppDao().upsert(
                        GuardedAppEntity(
                            packageName = pkg,
                            appName = AppInfoHelper.appLabel(getApplication(), pkg).ifBlank { label },
                            dailyLimitMinutes = 30,
                            enabled = true
                        )
                    )
                }
            }
            refreshUsage()
        }
    }

    fun startFocus(minutes: Int) {
        viewModelScope.launch {
            settings.startFocus(minutes)
            val endAt = settings.focusEndAt.first()
            if (endAt > 0) {
                Notifications.notifyFocus(getApplication(), endAt, "剩余 ${minutes} 分钟，受管应用已被锁定")
            }
        }
    }

    fun stopFocus() {
        viewModelScope.launch {
            settings.stopFocus()
            Notifications.cancelFocus(getApplication())
        }
    }

    fun setBedtime(enabled: Boolean, startHour: Int, endHour: Int) {
        viewModelScope.launch { settings.setBedtime(enabled, startHour, endHour) }
    }

    suspend fun installedApps(): List<InstalledApp> =
        AppInfoHelper.installedApps(getApplication())

    fun isGuardActive(): Boolean = GuardAccessibilityService.isRunning()
}
