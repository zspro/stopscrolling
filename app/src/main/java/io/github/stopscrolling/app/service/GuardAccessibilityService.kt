package io.github.stopscrolling.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import io.github.stopscrolling.app.FocusLockActivity
import io.github.stopscrolling.app.GatekeeperActivity
import io.github.stopscrolling.app.data.AppDatabase
import io.github.stopscrolling.app.data.BlockEventEntity
import io.github.stopscrolling.app.data.GuardedAppEntity
import io.github.stopscrolling.app.data.SettingsStore
import io.github.stopscrolling.app.data.UsageStatsHelper
import io.github.stopscrolling.app.util.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 守门员：通过无障碍事件监听前台应用切换。
 * 处理顺序：专注锁定 > 睡觉时段 > 宽限期 > 每日限额 > 80% 软预警。
 */
class GuardAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastTriggerAt = 0L
    private var graceRecheckScheduled = false

    /**
     * 从服务上下文启动 Activity。
     * 无障碍服务被系统绑定，豁免 Android 10+ 的后台启动 Activity 限制，
     * 自动授权链在设置界面里续推下一项时必须走这里。
     */
    fun launchActivity(intent: Intent): Boolean = try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Throwable) {
        false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 保留 XML 配置（窗口内容读取、手势等能力），只保证事件类型齐全
        serviceInfo = serviceInfo.apply {
            eventTypes = eventTypes or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        }
        current = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        // 自动授权进行中：守护逻辑挂起，避免干扰设置界面
        if (AutoGrantController.activeStepId != null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (pkg == packageName || pkg == "com.android.systemui") return
        // 系统弹窗 / 输入法等窗口切换不算
        if (event.className?.toString()?.startsWith("android.") == true) return

        handleForegroundChange(pkg)
    }

    private fun handleForegroundChange(pkg: String) {
        scope.launch {
            val context = applicationContext
            val db = AppDatabase.get(context)
            val app = db.guardedAppDao().find(pkg) ?: return@launch
            val settings = SettingsStore(context)
            val now = System.currentTimeMillis()

            // 1) 专注模式下：直接锁屏
            val focusEnd = settings.focusEndAt.first()
            if (focusEnd > now) {
                triggerFocusLock()
                return@launch
            }

            // 2) 睡觉时段：拦截且无宽限
            if (settings.bedtimeEnabled.first()) {
                val start = settings.bedtimeStartHour.first()
                val end = settings.bedtimeEndHour.first()
                if (UsageStatsHelper.inBedtimeWindow(start, end)) {
                    if (now - lastTriggerAt > DEBOUNCE_MS) {
                        lastTriggerAt = now
                        db.blockEventDao().insert(
                            BlockEventEntity(packageName = pkg, timestamp = now, kind = "BLOCKED")
                        )
                        launchGatekeeper(app, bedtime = true)
                    }
                    return@launch
                }
            }

            // 3) 宽限期生效中：放行，并安排到期复查（用户停留同一应用也能续拦）
            if (app.graceUntil > now) {
                scheduleGraceRecheck(app)
                return@launch
            }

            // 4) 超过每日限额：拦截
            if (UsageStatsHelper.isOverLimit(context, app)) {
                if (now - lastTriggerAt > DEBOUNCE_MS) {
                    lastTriggerAt = now
                    db.blockEventDao().insert(
                        BlockEventEntity(packageName = pkg, timestamp = now, kind = "BLOCKED")
                    )
                    launchGatekeeper(app, bedtime = false)
                }
                return@launch
            }

            // 5) 用量达到 80%：软预警（每应用每天一次）
            val limitMs = app.dailyLimitMinutes * 60_000L
            if (limitMs > 0) {
                val used = UsageStatsHelper.usageOf(context, pkg)
                if (used >= limitMs * 0.8) {
                    val warned = db.blockEventDao().countSince("WARN:$pkg", UsageStatsHelper.startOfDay()) > 0
                    if (!warned) {
                        db.blockEventDao().insert(
                            BlockEventEntity(packageName = pkg, timestamp = now, kind = "WARN:$pkg")
                        )
                        Notifications.notifyWarning(context, app.appName, app.dailyLimitMinutes)
                    }
                }
            }
        }
    }

    /** 宽限期到期后，如果用户仍停留在该应用，继续拦截 */
    private fun scheduleGraceRecheck(app: GuardedAppEntity) {
        if (graceRecheckScheduled) return
        graceRecheckScheduled = true
        scope.launch {
            delay((app.graceUntil - System.currentTimeMillis()).coerceAtLeast(1000L) + 2000L)
            graceRecheckScheduled = false
            val foreground = UsageStatsHelper.currentForegroundPackage(applicationContext)
            if (foreground == app.packageName &&
                app.graceUntil <= System.currentTimeMillis()
            ) {
                handleForegroundChange(app.packageName)
            }
        }
    }

    private fun launchGatekeeper(app: GuardedAppEntity, bedtime: Boolean) {
        GatekeeperActivity.launch(applicationContext, app.packageName, app.appName, bedtime)
    }

    private fun triggerFocusLock() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val admin = ComponentName(this, GuardDeviceAdminReceiver::class.java)
        if (dpm?.isAdminActive(admin) == true) {
            dpm.lockNow()
        } else {
            FocusLockActivity.launch(applicationContext)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        current = null
        super.onDestroy()
    }

    companion object {
        private const val DEBOUNCE_MS = 15_000L

        @Volatile var current: GuardAccessibilityService? = null
            private set

        fun isRunning(): Boolean = current != null
    }
}
