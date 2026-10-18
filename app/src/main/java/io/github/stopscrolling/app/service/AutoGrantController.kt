package io.github.stopscrolling.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.github.stopscrolling.app.OnboardingActivity
import io.github.stopscrolling.app.util.PermissionChecker

/**
 * 自动授权引擎：利用无障碍能力读取系统设置界面，代替用户点击开关/按钮。
 *
 * 关键策略（对照原版行为 + Android 系统限制得出的教训）：
 * 1. 只点"本应用所在行"内部或行可到达的开关，绝不点列表里其他应用的开关；
 * 2. 详情页启发式：屏幕同时出现本应用名 + 唯一一个未勾选开关时才点它；
 * 3. 弹窗按钮先精确匹配「允许」，包含匹配时排除「不允许」；
 * 4. 轮询发现回到自己界面时立即停止（交还 ON_RESUME 链条续推）；
 * 5. 超时只停止本步轮询，由引导页在返回前台时重试或手动接管。
 */
object AutoGrantController {

    @Volatile
    var activeStepId: String? = null
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var onDone: ((String, Boolean) -> Unit)? = null
    private var onStopped: (() -> Unit)? = null
    private var appLabel: String = ""
    private var attempts = 0
    private var scrolls = 0
    private var lastClickAt = 0L

    /** 本轮 tick 执行的最后动作（显示在盖板上，写进诊断） */
    @Volatile
    private var lastAction = "等待"

    /** 需要盖板遮蔽界面的步骤。盖板挂无障碍服务（TYPE_ACCESSIBILITY_OVERLAY），
     *  不依赖悬浮窗权限，所以悬浮窗这一步本身也能被盖板盖着自动完成 */
    private val coveredSteps = setOf("overlay", "usage", "admin", "battery")
    private val stepTitles = mapOf(
        "overlay" to "开启「悬浮窗权限」",
        "usage" to "开启「使用情况访问权限」",
        "admin" to "激活设备管理员",
        "battery" to "申请电池优化白名单"
    )

    private const val TICK_MS = 700L
    private const val MAX_ATTEMPTS = 90 // 约 1 分钟后放弃，回落到手动
    private const val MAX_SCROLLS = 12
    private const val CLICK_COOLDOWN_MS = 1200L
    private const val MAX_SCAN_NODES = 1500

    // ── 诊断追踪：记录引擎每一步"看到了什么/做了什么"，随包可导出 ──
    private val trace = ArrayDeque<String>()
    private val traceLock = Any()

    private fun logTrace(msg: String) {
        val line = "[%02d.%03d] %s".format(
            (SystemClock.elapsedRealtime() / 1000) % 100,
            SystemClock.elapsedRealtime() % 1000,
            msg
        )
        synchronized(traceLock) {
            trace.addLast(line)
            while (trace.size > 300) trace.removeFirst()
        }
    }

    /** 导出完整诊断日志（盖板"复制诊断"按钮调用） */
    fun dumpTrace(): String = synchronized(traceLock) {
        buildString {
            appendLine("=== 放下手机 自动授权诊断 ===")
            appendLine("机型: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} Android ${android.os.Build.VERSION.RELEASE}")
            appendLine("步骤: $activeStepId")
            trace.forEach { appendLine(it) }
        }
    }

    private fun serviceDiag(service: AccessibilityService): String {
        return try {
            val info = service.serviceInfo
            val hasWindowsFlag = (info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) != 0
            val canGesture = (info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0
            val winCount = try {
                (service.windows ?: emptyList()).size
            } catch (_: Throwable) {
                -1
            }
            "flags(windows=$hasWindowsFlag,gesture=$canGesture) windows=$winCount"
        } catch (_: Throwable) {
            "serviceInfo不可读"
        }
    }

    fun start(
        stepId: String,
        label: String,
        done: (String, Boolean) -> Unit,
        stopped: (() -> Unit)? = null
    ) {
        stop()
        activeStepId = stepId
        appLabel = label
        onDone = done
        onStopped = stopped
        attempts = 0
        scrolls = 0
        handler.postDelayed(tickRunnable, TICK_MS)
    }

    fun stop() {
        activeStepId = null
        onDone = null
        onStopped = null
        handler.removeCallbacks(tickRunnable)
    }

    /** 用户点盖板上的「停止」：终止链条、移除盖板、回到应用 */
    fun userCancel() {
        val wasActive = activeStepId != null
        val stoppedCallback = onStopped
        stop()
        AutoGrantCover.hide()
        if (wasActive) returnToApp()
        stoppedCallback?.invoke()
    }

    private fun returnToApp() {
        val service = GuardAccessibilityService.current ?: return
        try {
            val intent = Intent(service.applicationContext, OnboardingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            service.launchActivity(intent)
        } catch (_: Throwable) {
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            val step = activeStepId ?: return
            val service = GuardAccessibilityService.current
            if (service == null) {
                attempts++
                logTrace("无障碍服务未运行 current=null (尝试 $attempts)")
                if (attempts > MAX_ATTEMPTS) {
                    finish(step, false)
                    return
                }
                scheduleNext()
                return
            }
            attempts++
            if (attempts == 1 || attempts % 5 == 0) {
                logTrace("tick#$attempts [$step] ${serviceDiag(service)}")
            }
            if (attempts > MAX_ATTEMPTS) {
                logTrace("超时放弃 $step")
                finish(step, false)
                return
            }
            // 需要遮蔽的步骤：盖上盖板（挂无障碍服务，不抢焦点、不收触摸，
            // 底下窗口照常被读取和点击；用户看到的只有盖板）
            if (step in coveredSteps && !AutoGrantCover.shown) {
                AutoGrantCover.show(
                    service,
                    stepTitles[step] ?: "正在授权…"
                ) { userCancel() }
            }
            var action = "等待"
            val ok = try {
                val r = tick(service, step)
                action = lastAction
                r
            } catch (e: Exception) {
                action = "异常:${e.javaClass.simpleName}"
                logTrace("tick异常: ${e}")
                false
            }
            AutoGrantCover.updateStatus("$action · 第 $attempts/$MAX_ATTEMPTS 轮")
            if (ok) {
                finish(step, true)
                return
            }
            scheduleNext()
        }
    }

    private fun scheduleNext() {
        if (activeStepId != null) handler.postDelayed(tickRunnable, TICK_MS)
    }

    private fun finish(step: String, success: Boolean) {
        activeStepId = null
        handler.removeCallbacks(tickRunnable)
        if (!success) {
            // 失败/超时：移除盖板并回到引导页（若不在前台），交还重试或手动
            if (step != "notifications") {
                AutoGrantCover.hide()
                returnToApp()
            }
        }
        val done = onDone
        onDone = null
        onStopped = null
        done?.invoke(step, success)
    }

    private fun tick(service: AccessibilityService, step: String): Boolean {
        val ctx = service.applicationContext
        val granted = when (step) {
            "accessibility" -> PermissionChecker.accessibilityServiceEnabled(ctx)
            "usage" -> PermissionChecker.hasUsageAccess(ctx)
            "overlay" -> android.provider.Settings.canDrawOverlays(ctx)
            "admin" -> PermissionChecker.isAdminActive(ctx)
            "battery" -> PermissionChecker.isIgnoringBatteryOptimizations(ctx)
            "notifications" -> android.os.Build.VERSION.SDK_INT < 33 ||
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            else -> true
        }
        if (granted) return true

        // 选根窗口：必须跳过自家窗口（盖板/停止按钮都是本应用的悬浮窗），
        // 否则会被误判为"用户回到自己界面"，链条当场自杀
        val root = activeRootOutsideSelf(service)
        if (root == null) {
            lastAction = "看不到设置页"
            if (attempts % 5 == 1) {
                logTrace("[$step] 无可用外部窗口（可能全被自家盖板覆盖或 windows 空）")
            }
            return false
        }
        lastAction = "页面:${root.packageName.toString().substringAfterLast('.')}"

        // 回到了自己的界面（非通知弹窗流程）→ 停止轮询，链条由 ON_RESUME 续推
        if (root.packageName == service.packageName && step != "notifications") {
            logTrace("[$step] 检测到回到自身界面，停止轮询")
            finish(step, false)
            return false
        }

        return when (step) {
            // 确认弹窗优先：一旦弹出就必须先点掉，再找列表行
            "accessibility" ->
                clickConfirm(root, listOf("确定", "允许", "开启", "OK", "OK, got it")) ||
                    grantViaAppRowAndSwitch(service, root)
            "usage", "overlay" ->
                clickConfirm(root, listOf("允许", "确定", "Allow", "OK")) ||
                    grantViaAppRowAndSwitch(service, root)
            "admin" -> clickAction(root, listOf("激活", "启用", "Activate", "Turn on", "Aktivieren"))
            "battery", "notifications" -> clickAllow(root)
            else -> false
        }
    }

    /**
     * 取"非本应用"窗口的根节点：遍历所有窗口（需 flagRetrieveInteractiveWindows，
     * 已在 accessibility_service_config.xml 声明，否则 windows 恒为空、自动化必死），
     * 跳过自家的盖板/停止键（TYPE_ACCESSIBILITY_OVERLAY）、键盘（TYPE_INPUT_METHOD），
     * 从最顶层往下取第一个真实系统/应用窗口——保证弹窗场景选中的是弹窗而非底下列表。
     */
    private fun activeRootOutsideSelf(service: AccessibilityService): AccessibilityNodeInfo? {
        val windows: List<AccessibilityWindowInfo> = try {
            service.windows ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
        if (windows.isNotEmpty()) {
            val ordered = windows.sortedByDescending { it.layer }
            for (window in ordered) {
                when (window.type) {
                    AccessibilityWindowInfo.TYPE_INPUT_METHOD,
                    AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> continue
                }
                val root = try {
                    window.root
                } catch (_: Throwable) {
                    null
                }
                val pkg = root?.packageName?.toString() ?: continue
                if (pkg == service.packageName) continue
                return root
            }
            return null
        }
        val root = service.rootInActiveWindow ?: return null
        return if (root.packageName == service.packageName) null else root
    }

    /** 应用列表/详情页通用路径：行内开关 → 行点击 → 详情页唯一开关 → 滚动 */
    private fun grantViaAppRowAndSwitch(
        service: AccessibilityService,
        root: AccessibilityNodeInfo
    ): Boolean {
        // 应用名匹配要求接近全等，避免「微信」误命中「微信读书」这类包含关系
        val labelNode = findNodeByText(root, listOf(appLabel), exact = true)
            ?: findNodeByText(root, listOf(appLabel), exact = false)
        if (labelNode != null) {
            // 1) 本应用所在行内部有未勾选开关（列表式开关，如悬浮窗列表）→ 只点这个
            val row = labelNode.clickableAncestor()
            val rowSwitch = row?.let { findUncheckedSwitchIn(it) }
            if (rowSwitch != null) {
                lastAction = "点行内开关"
                logTrace("[$activeStepId] 命中行内开关 ${boundsOf(rowSwitch)}")
                if (rowSwitch.performClickSafe()) scrolls = 0
                return false
            }
            // 2) 行内无开关 → 点行本身进入详情页
            if (row != null) {
                lastAction = "点行进详情"
                logTrace("[$activeStepId] 点击应用行 ${boundsOf(row)}")
                if (row.performClickSafe()) scrolls = 0
                return false
            }
            // 3) 行不可点击（标签在标题区）→ 详情页启发式：
            //    a. 全页唯一未勾选开关直接点；b. 否则点"行尾未勾选开关"所在的
            //    可点击行（借鉴别刷了 UNIQUE_TRAILING_SWITCH 策略）；c. 都不行就滚动
            val switches = findUncheckedSwitches(root)
            if (switches.size == 1) {
                lastAction = "点详情开关"
                logTrace("[$activeStepId] 唯一开关 ${boundsOf(switches[0])}")
                switches[0].performClickSafe()
                return false
            }
            for (sw in switches) {
                val text = (sw.text?.toString() ?: "") +
                    (sw.contentDescription?.toString() ?: "")
                if (text.contains("允许", true) || text.contains("不允许", true)) continue
                val target = sw.clickableAncestor() ?: sw
                if (target.performClickSafe()) return false
            }
            if (scrolls < MAX_SCROLLS && cooldownOk()) {
                scrollDown(service)
                scrolls++
                lastAction = "详情页滚动 $scrolls"
                logTrace("[$activeStepId] 详情页滚动 $scrolls，未见开关")
                lastClickAt = System.currentTimeMillis()
            }
            return false
        }
        // 4) 找不到本应用行 → 滑动列表查找
        if (scrolls == 0 && labelNode == null) {
            lastAction = "找应用行…"
        }
        if (scrolls < MAX_SCROLLS && cooldownOk()) {
            scrollDown(service)
            scrolls++
            lastClickAt = System.currentTimeMillis()
        }
        return false
    }

    private fun cooldownOk(): Boolean =
        System.currentTimeMillis() - lastClickAt > CLICK_COOLDOWN_MS

    // ---- 节点查找与操作 ----

    private fun findNodeByText(
        root: AccessibilityNodeInfo,
        keywords: List<String>,
        exact: Boolean
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var scanned = 0
        while (queue.isNotEmpty() && scanned < MAX_SCAN_NODES) {
            val node = queue.removeFirst()
            scanned++
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val hit = keywords.any { kw ->
                // 非精确匹配允许长文本（如设备管理页的「激活此设备管理应用」命中「激活」），
                // 上限放宽到 kw+14 字符；误点风险由调用方的排除词（denyWords）兜底
                if (exact) text == kw || desc == kw
                else (text.isNotEmpty() && text.length < kw.length + 14 &&
                    text.contains(kw, ignoreCase = true)) ||
                    (desc.isNotEmpty() && desc.length < kw.length + 14 &&
                        desc.contains(kw, ignoreCase = true))
            }
            if (hit) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    /** 找一个未勾选的 Switch / Checkable 控件 */
    private fun findUncheckedSwitchIn(node: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findUncheckedSwitches(node, 200).firstOrNull()

    /**
     * 找未勾选的可勾选控件。不看类名：ROM 自制控件（如 AOSP 的 SwitchBar、
     * 各家定制 Switch）类名五花八门，按 isCheckable 判断更可靠；
     * 误点由"只点本应用所在行"和"全页唯一"两个约束兜底。
     */
    private fun findUncheckedSwitches(
        root: AccessibilityNodeInfo,
        limit: Int = MAX_SCAN_NODES
    ): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var scanned = 0
        while (queue.isNotEmpty() && scanned < limit) {
            val node = queue.removeFirst()
            scanned++
            if (node.isCheckable && !node.isChecked) {
                result.add(node)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return result
    }

    private fun boundsOf(node: AccessibilityNodeInfo): String = try {
        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        "(${r.left},${r.top},${r.width()}x${r.height()})"
    } catch (_: Throwable) {
        "(?)"
    }

    private fun AccessibilityNodeInfo.clickableAncestor(): AccessibilityNodeInfo? {
        var node: AccessibilityNodeInfo? = this
        var hops = 0
        while (node != null && hops < 8) {
            if (node.isClickable) return node
            node = node.parent
            hops++
        }
        return null
    }

    /**
     * 点击执行：优先 ACTION_CLICK（不走输入管线、不怕遮挡窗口吃触摸），
     * 失败再手势点击兜底（部分 ROM 的设置页节点 ACTION_CLICK 无效）。
     */
    private fun AccessibilityNodeInfo.performClickSafe(): Boolean {
        if (!cooldownOk()) return false
        val node = if (isClickable) this else clickableAncestor() ?: return false
        val byAction = try {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (_: Exception) {
            false
        }
        if (byAction) {
            lastClickAt = System.currentTimeMillis()
            return true
        }
        val service = GuardAccessibilityService.current ?: return false
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return false
        val path = Path().apply {
            moveTo(rect.exactCenterX(), rect.exactCenterY())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        val dispatched = try {
            service.dispatchGesture(gesture, null, null)
        } catch (_: Exception) {
            false
        }
        if (dispatched) lastClickAt = System.currentTimeMillis()
        return dispatched
    }

    /** 确认按钮（如无障碍开启前的「确定/允许」弹窗），含安全排除否定词 */
    private fun clickConfirm(root: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        val denyWords = listOf("取消", "不允许", "拒绝", "Cancel", "Deny", "Don't allow")
        // 精确匹配优先
        findNodeByText(root, keywords, exact = true)?.clickableAncestor()?.let { node ->
            val text = (node.text?.toString() ?: "") + (node.contentDescription?.toString() ?: "")
            if (denyWords.none { text.contains(it, ignoreCase = true) }) {
                return node.performClickSafe()
            }
        }
        // 包含匹配：搜索过程中同时排除拒绝词，避免「不允许…」抢先命中后整轮放弃
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var scanned = 0
        while (queue.isNotEmpty() && scanned < MAX_SCAN_NODES) {
            val node = queue.removeFirst()
            scanned++
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val isAllow = keywords.any { (text.contains(it, true) || desc.contains(it, true)) }
            val isDeny = denyWords.any { (text.contains(it, true) || desc.contains(it, true)) }
            if (isAllow && !isDeny) {
                node.clickableAncestor()?.let { target ->
                    if (target.performClickSafe()) return true
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    /**
     * 弹窗按钮点击：先精确匹配「允许」，再包含匹配（排除「不允许」）。
     */
    private fun clickAllow(root: AccessibilityNodeInfo): Boolean {
        val allowWords = listOf("允许", "Allow", "Zulassen")
        val denyWords = listOf("不允许", "拒绝", "Don't allow", "Deny", "Nicht zulassen")
        // 精确匹配优先
        for (kw in allowWords) {
            val node = findNodeByText(root, listOf(kw), exact = true)?.clickableAncestor()
            if (node != null) {
                return node.performClickSafe()
            }
        }
        // 包含匹配，排除拒绝词
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var scanned = 0
        while (queue.isNotEmpty() && scanned < MAX_SCAN_NODES) {
            val node = queue.removeFirst()
            scanned++
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val isAllow = allowWords.any { kw ->
                (text.contains(kw, ignoreCase = true) || desc.contains(kw, ignoreCase = true))
            }
            val isDeny = denyWords.any { kw ->
                (text.contains(kw, ignoreCase = true) || desc.contains(kw, ignoreCase = true))
            }
            if (isAllow && !isDeny) {
                val target = node.clickableAncestor()
                if (target != null) {
                    return target.performClickSafe()
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun clickAction(root: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        val node = findNodeByText(root, keywords, exact = false)?.clickableAncestor() ?: return false
        return node.performClickSafe()
    }

    private fun scrollDown(service: AccessibilityService) {
        val metrics = service.resources.displayMetrics
        val cx = metrics.widthPixels / 2f
        val path = Path().apply {
            moveTo(cx, metrics.heightPixels * 0.72f)
            lineTo(cx, metrics.heightPixels * 0.32f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 250))
            .build()
        try {
            service.dispatchGesture(gesture, null, null)
        } catch (_: Exception) {
        }
    }
}
