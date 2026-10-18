package io.github.stopscrolling.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.stopscrolling.app.MainViewModel
import io.github.stopscrolling.app.data.UsageStatsHelper
import io.github.stopscrolling.app.service.GuardAccessibilityService
import io.github.stopscrolling.app.util.PermissionChecker
import io.github.stopscrolling.app.util.RomCompat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val home by vm.home.collectAsStateWithLifecycle()
    val focusRemain = (home.focusEndAt - System.currentTimeMillis()).coerceAtLeast(0)

    // 关键修复：从系统设置页返回时自动刷新权限状态（设备管理员 / 无障碍等）
    var permTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val usageGranted = remember(permTick) { PermissionChecker.hasUsageAccess(context) }
    val accessibilityGranted = remember(permTick) { GuardAccessibilityService.isRunning() }
    val overlayGranted = remember(permTick) { Settings.canDrawOverlays(context) }
    val adminGranted = remember(permTick) { PermissionChecker.isAdminActive(context) }
    val batteryGranted = remember(permTick) { PermissionChecker.isIgnoringBatteryOptimizations(context) }
    val notifGranted = remember(permTick) {
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("权限", style = MaterialTheme.typography.titleMedium)
                Text(
                    "以下权限全部开启后，守护才能稳定工作。设备管理员开启后，卸载本应用需先在系统设置中取消激活——这是防卸载加固的一部分。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                PermCard(
                    title = "用量统计权限",
                    desc = "用于统计各应用今日使用时长",
                    granted = usageGranted,
                    onFix = {
                        context.startActivity(
                            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                )
            }
            item {
                PermCard(
                    title = "无障碍服务",
                    desc = "用于检测前台应用切换并触发拦截",
                    granted = accessibilityGranted,
                    onFix = {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                )
            }
            item {
                PermCard(
                    title = "悬浮窗权限",
                    desc = "允许拦截页在其他应用之上弹出",
                    granted = overlayGranted,
                    onFix = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                )
            }
            item {
                PermCard(
                    title = "设备管理员",
                    desc = "专注模式一键锁屏；同时防止应用被直接卸载",
                    granted = adminGranted,
                    onFix = {
                        context.startActivity(
                            Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                putExtra(
                                    android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                                    android.content.ComponentName(
                                        context,
                                        io.github.stopscrolling.app.service.GuardDeviceAdminReceiver::class.java
                                    )
                                )
                                putExtra(
                                    android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                    "用于专注模式一键锁屏与防卸载加固"
                                )
                            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                )
            }
            item {
                PermCard(
                    title = "忽略电池优化",
                    desc = "防止服务被系统杀掉",
                    granted = batteryGranted,
                    onFix = {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                )
            }
            item {
                PermCard(
                    title = "通知权限",
                    desc = "限额预警与专注倒计时通知",
                    granted = notifGranted,
                    onFix = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            (context as? android.app.Activity)?.requestPermissions(
                                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1
                            )
                        }
                    }
                )
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text("专注锁定", style = MaterialTheme.typography.titleMedium)
                Text(
                    "锁定期间，任何受管应用都无法打开，屏幕将被强制锁定。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        if (focusRemain > 0) {
                            Text(
                                "专注中，剩余 ${UsageStatsHelper.formatDuration(focusRemain)}",
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { vm.stopFocus() }) { Text("结束专注") }
                        } else {
                            Text("当前没有进行中的专注。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(15, 25, 45, 60).forEach { minutes ->
                                    Button(onClick = { vm.startFocus(minutes) }) {
                                        Text("$minutes 分钟")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text("睡觉时段", style = MaterialTheme.typography.titleMedium)
                Text(
                    "在设定的时间段内，所有受管应用一律拦截，且没有「再刷 5 分钟」宽限。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                BedtimeCard(vm = vm)
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text("ROM 兼容性", style = MaterialTheme.typography.titleMedium)
                Text(
                    "国产 ROM 的省电策略会悄悄杀掉守护。按你的手机品牌，把「放下手机」加入自启动白名单并设为不省电限制——每条入口都带兜底，点了必有响应。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                RomCard()
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text("关于", style = MaterialTheme.typography.titleMedium)
            }
            item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "放下手机 StopScrolling —— 一个开源的防沉迷工具，帮你少刷短视频、多看世界。\n\n本应用为原创开源实现，不收集、不上传任何个人数据。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            context.startActivity(
                                Intent(context, io.github.stopscrolling.app.OnboardingActivity::class.java)
                            )
                        }) {
                            Text("重新运行权限引导")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BedtimeCard(vm: MainViewModel) {
    val home by vm.home.collectAsStateWithLifecycle()
    // 拖动过程只改本地状态，松手才写库（否则每滑一格写一次 DataStore）
    var pendingStart by remember(home.bedtimeStartHour) {
        mutableIntStateOf(home.bedtimeStartHour)
    }
    var pendingEnd by remember(home.bedtimeEndHour) {
        mutableIntStateOf(home.bedtimeEndHour)
    }

    Card {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("启用睡觉时段", fontWeight = FontWeight.SemiBold)
                    Text(
                        "%02d:00 - %02d:00".format(pendingStart, pendingEnd),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(checked = home.bedtimeEnabled, onCheckedChange = { on ->
                    vm.setBedtime(on, pendingStart, pendingEnd)
                })
            }
            Spacer(Modifier.height(8.dp))
            Text("开始：${"%02d:00".format(pendingStart)}", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = pendingStart.toFloat(),
                onValueChange = { pendingStart = it.toInt() },
                onValueChangeFinished = {
                    vm.setBedtime(home.bedtimeEnabled, pendingStart, pendingEnd)
                },
                valueRange = 0f..23f,
                steps = 22
            )
            Text("结束：${"%02d:00".format(pendingEnd)}", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = pendingEnd.toFloat(),
                onValueChange = { pendingEnd = it.toInt() },
                onValueChangeFinished = {
                    vm.setBedtime(home.bedtimeEnabled, pendingStart, pendingEnd)
                },
                valueRange = 0f..23f,
                steps = 22
            )
        }
    }
}

@Composable
private fun PermCard(
    title: String,
    desc: String,
    granted: Boolean,
    onFix: () -> Unit
) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                if (granted) "已开启" else "未开启",
                color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(Modifier.padding(4.dp))
            if (!granted) {
                Button(onClick = onFix) { Text("去开启") }
            }
        }
    }
}

@Composable
private fun RomCard() {
    val context = LocalContext.current
    val romName = remember { RomCompat.detect() }
    val links = remember { RomCompat.jumps() }
    Card {
        Column(Modifier.padding(16.dp)) {
            Text("当前 ROM：$romName", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            links.forEach { link ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(link.title)
                        Text(
                            link.desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { RomCompat.launch(context, link) }) {
                        Text("去设置")
                    }
                }
            }
            Text(
                "跳转失败时会自动落到本应用详情页。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
