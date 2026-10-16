package io.github.stopscrolling.app

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.github.stopscrolling.app.data.SettingsStore
import io.github.stopscrolling.app.service.AutoGrantController
import io.github.stopscrolling.app.service.AutoGrantCover
import io.github.stopscrolling.app.service.GuardAccessibilityService
import io.github.stopscrolling.app.service.GuardDeviceAdminReceiver
import io.github.stopscrolling.app.ui.theme.StopScrollTheme
import io.github.stopscrolling.app.util.PermissionChecker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 权限引导清单项 */
private data class PermStep(
    val id: String,
    val title: String,
    val desc: String,
    val granted: Boolean
)

private val Accent = Color(0xFF7BC4A2)
private val DarkBg = Color(0xFF101418)
private val SubText = Color(0xFF9AA6B2)
private val CardBg = Color(0xFF171D24)
private val Muted = Color(0xFF5A6672)

/**
 * 首次启动引导：欢迎 → 使用原理 → 权限引导。
 * 权限页支持"自动授权链"：开启无障碍回到应用后，自动继续弹出下一项权限申请，
 * 直到全部就绪（系统限制，实际每项仍需用户在系统界面确认一次）。
 */
class OnboardingActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_AUTO_STEP = "io.github.stopscrolling.extra.AUTO_STEP"
    }

    private var autoChain by mutableStateOf(false)
    private var chainJob: Job? = null
    private var steps by mutableStateOf<List<PermStep>>(emptyList())
    private var pendingNotifRequest = false

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshSteps()
            scheduleNextIfChaining()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 返回前台时刷新权限状态；若处于自动授权链则继续下一项
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    refreshSteps()
                    if (pendingNotifRequest) {
                        pendingNotifRequest = false
                        launchStep("notifications")
                    } else {
                        scheduleNextIfChaining()
                    }
                }
            }
        )

        setContent {
            StopScrollTheme {
                OnboardingScreen(
                    steps = steps,
                    autoChain = autoChain,
                    onStartChain = {
                        autoChain = true
                        refreshSteps()
                        scheduleNextIfChaining()
                    },
                    onStopChain = {
                        autoChain = false
                        chainJob?.cancel()
                        AutoGrantController.stop()
                        AutoGrantCover.hide()
                    },
                    onLaunchStep = { launchStep(it) },
                    onFinish = {
                        AutoGrantController.stop()
                        AutoGrantCover.hide()
                        lifecycleScope.launch {
                            SettingsStore(applicationContext).setOnboardingDone()
                        }
                        finish()
                    }
                )
            }
        }
        refreshSteps()
    }

    private fun buildSteps(): List<PermStep> = listOf(
        PermStep(
            "accessibility", "无障碍服务",
            "核心权限。用于检测你打开了哪个应用，超限时弹出拦截页。",
            PermissionChecker.accessibilityServiceEnabled(this)
        ),
        PermStep(
            "overlay", "悬浮窗权限",
            "让拦截页能在其他应用之上弹出；开启后其余权限将自动完成。",
            Settings.canDrawOverlays(this)
        ),
        PermStep(
            "usage", "用量统计权限",
            "统计各应用的每日使用时长，判断是否超限。",
            PermissionChecker.hasUsageAccess(this)
        ),
        PermStep(
            "admin", "设备管理员",
            "专注模式一键锁屏；同时防止应用被直接卸载。",
            PermissionChecker.isAdminActive(this)
        ),
        PermStep(
            "battery", "忽略电池优化",
            "防止守护服务被系统省电策略杀掉。",
            PermissionChecker.isIgnoringBatteryOptimizations(this)
        ),
        PermStep(
            "notifications", "通知权限",
            "限额预警与专注倒计时通知。",
            Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
    )

    private fun refreshSteps() {
        steps = buildSteps()
    }

    private fun scheduleNextIfChaining() {
        if (!autoChain) return
        if (steps.none { !it.granted }) {
            autoChain = false
            AutoGrantCover.hide()
            return
        }
        chainJob?.cancel()
        chainJob = lifecycleScope.launch {
            delay(800)
            steps.firstOrNull { !it.granted }?.let { launchStep(it.id) }
        }
    }

    private fun launchStep(id: String) {
        // 启动自动授权：无障碍服务代点系统设置里的开关/按钮；
        // 成功→继续链条；失败/超时→移除盖板回到引导页，返回前台时自动重试
        val appLabel = try {
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (_: Exception) {
            "放下手机"
        }
        AutoGrantController.start(
            id,
            appLabel,
            done = { stepId, success ->
                runOnUiThread {
                    // 通知权限走运行时对话框回调推进，避免重复触发
                    if (stepId != "notifications") {
                        refreshSteps()
                        if (success) scheduleNextIfChaining()
                    }
                }
            },
            stopped = { autoChain = false }
        )
        when (id) {
            "notifications" -> {
                // 权限对话框必须可见，先把盖板撤掉
                AutoGrantCover.hide()
                if (Build.VERSION.SDK_INT >= 33) {
                    // 权限对话框只能在应用前台弹出；在后台时先由服务把自己带回前台
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        val ok = GuardAccessibilityService.current?.launchActivity(
                            Intent(this, OnboardingActivity::class.java).apply {
                                putExtra(EXTRA_AUTO_STEP, id)
                                addFlags(
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                                )
                            }
                        ) ?: false
                        if (!ok) pendingNotifRequest = true
                    }
                }
            }
            else -> openFromForegroundOrService(buildSettingsIntent(id))
        }
    }

    private fun buildSettingsIntent(id: String): Intent = when (id) {
        "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        "usage" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        "overlay" -> Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        "admin" -> Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(
                DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                ComponentName(this@OnboardingActivity, GuardDeviceAdminReceiver::class.java)
            )
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "用于专注模式一键锁屏与防卸载加固"
            )
        }
        "battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        else -> Intent(Settings.ACTION_SETTINGS)
    }

    /**
     * Android 10+ 禁止后台应用启动 Activity。在设置界面里自动授权时
     * 本 Activity 处于后台，必须经无障碍服务启动（系统绑定服务豁免该限制）。
     */
    private fun openFromForegroundOrService(intent: Intent) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            startActivity(intent)
        } else {
            val ok = GuardAccessibilityService.current?.launchActivity(intent) ?: false
            if (!ok) {
                // 无服务时兜底尝试（可能被系统忽略，引导页回到前台后 ON_RESUME 会重试）
                try {
                    startActivity(intent)
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getStringExtra(EXTRA_AUTO_STEP) == "notifications") {
            pendingNotifRequest = true
        }
    }
}

@Composable
private fun OnboardingScreen(
    steps: List<PermStep>,
    autoChain: Boolean,
    onStartChain: () -> Unit,
    onStopChain: () -> Unit,
    onLaunchStep: (String) -> Unit,
    onFinish: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { 3 })

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> WelcomePage()
                1 -> HowItWorksPage()
                else -> PermissionPage(
                    steps = steps,
                    autoChain = autoChain,
                    onStartChain = onStartChain,
                    onStopChain = onStopChain,
                    onLaunchStep = onLaunchStep,
                    onFinish = onFinish
                )
            }
        }

        // 页面指示点
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            repeat(3) { i ->
                val active = pagerState.currentPage == i
                Box(
                    modifier = Modifier
                        .size(if (active) 10.dp else 6.dp)
                        .background(if (active) Accent else Color(0xFF3A4450), CircleShape)
                )
            }
        }
    }
}

@Composable
private fun WelcomePage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(Accent, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("放下", color = DarkBg, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(40.dp))
        Text("放下手机", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("把注意力还给生活。", color = SubText, fontSize = 16.sp)
        Spacer(Modifier.height(48.dp))
        Text(
            "一个开源、离线、不收集任何数据的\n屏幕时间守护工具",
            color = SubText,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp
        )
        Spacer(Modifier.height(24.dp))
        Text("向左滑动开始 →", color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun HowItWorksPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("三步开始", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(36.dp))
        val items = listOf(
            "1" to "选择要管理的应用\n并设定每日使用限额",
            "2" to "开启守护权限\n应用超限时自动拦截",
            "3" to "拦截页帮你冷静\n或开启专注 / 睡觉时段"
        )
        items.forEach { (num, text) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color(0xFF1C232B), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(num, color = Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(16.dp))
                Text(text, color = Color.White, fontSize = 15.sp, lineHeight = 22.sp)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("向左滑动继续 →", color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun PermissionPage(
    steps: List<PermStep>,
    autoChain: Boolean,
    onStartChain: () -> Unit,
    onStopChain: () -> Unit,
    onLaunchStep: (String) -> Unit,
    onFinish: () -> Unit
) {
    val allGranted = steps.isNotEmpty() && steps.all { it.granted }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(72.dp))
        Text("开启守护", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "开启无障碍和悬浮窗后，其余权限将在盖板下自动完成",
            color = SubText,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            steps.forEachIndexed { index, step ->
                PermRow(
                    index = index + 1,
                    title = step.title,
                    desc = step.desc,
                    granted = step.granted,
                    onClick = { onLaunchStep(step.id) }
                )
            }
        }

        if (allGranted) {
            Button(
                onClick = onFinish,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) {
                Text(
                    "全部就绪，开始使用",
                    color = DarkBg,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(24.dp))
        } else {
            Button(
                onClick = if (autoChain) onStopChain else onStartChain,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (autoChain) Color(0xFF3A4450) else Accent
                )
            ) {
                Text(
                    if (autoChain) "暂停自动授权" else "一键自动授权（推荐）",
                    color = if (autoChain) Color.White else DarkBg,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            TextButton(onClick = onFinish, modifier = Modifier.padding(top = 4.dp)) {
                Text("先跳过，稍后在设置里开启", color = Muted, fontSize = 13.sp)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermRow(
    index: Int,
    title: String,
    desc: String,
    granted: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(if (granted) Accent else Color(0xFF2A333D), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (granted) "✓" else "$index",
                    color = if (granted) DarkBg else Color(0xFF8A95A1),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(desc, color = SubText, fontSize = 11.sp, lineHeight = 15.sp)
            }
            Text(
                if (granted) "已开启" else "去开启",
                color = if (granted) Accent else Color(0xFFB9C4CF),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
