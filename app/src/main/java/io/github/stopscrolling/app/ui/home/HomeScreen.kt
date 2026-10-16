package io.github.stopscrolling.app.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.stopscrolling.app.MainViewModel
import io.github.stopscrolling.app.data.GuardedAppEntity
import io.github.stopscrolling.app.data.UsageStatsHelper
import io.github.stopscrolling.app.util.PermissionChecker
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: MainViewModel,
    onAddApps: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val home by vm.home.collectAsState()
    val guarded by vm.guardedApps.collectAsState()

    // 回到前台时刷新用量和拦截统计
    var permTick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                vm.refreshUsage()
                permTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val missingPerms = PermissionChecker.missingGaps(context, permTick)

    val todayTotal = home.usageByPackage.values.sum()
    val focusRemain = (home.focusEndAt - System.currentTimeMillis()).coerceAtLeast(0)

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("放下手机", fontWeight = FontWeight.Bold) })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddApps,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("添加应用") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (missingPerms.isNotEmpty()) {
                item(key = "perm_banner") {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "守护尚未完全开启",
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    "还差 ${missingPerms.size} 项：${missingPerms.joinToString("、")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            TextButton(onClick = onOpenSettings) { Text("去开启") }
                        }
                    }
                }
            }

            if (home.bedtimeText != null) {
                item(key = "bedtime") {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Bedtime, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                home.bedtimeText!! + if (home.bedtimeActive) "，受管应用已全部拦截" else "",
                                fontWeight = if (home.bedtimeActive) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            item {
                Card(colors = CardDefaults.cardColors()) {
                    Column(Modifier.padding(20.dp)) {
                        Text("今日屏幕时间", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            UsageStatsHelper.formatDuration(todayTotal),
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "今日已拦截 ${home.blockedToday} 次",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (home.topApps.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            Text("今日使用 Top 5", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(8.dp))
                            home.topApps.forEachIndexed { index, topApp ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "${index + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.width(20.dp)
                                    )
                                    Text(topApp.label, Modifier.weight(1f), fontSize = 14.sp)
                                    Text(
                                        "${topApp.minutes} 分钟",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (focusRemain > 0) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("专注中", fontWeight = FontWeight.SemiBold)
                                Text("剩余 ${UsageStatsHelper.formatDuration(focusRemain)}，受管应用已锁定")
                            }
                            IconButton(onClick = { vm.stopFocus() }) {
                                Icon(Icons.Filled.Stop, contentDescription = "结束专注")
                            }
                        }
                    }
                }
            } else {
                item {
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            Text("专注锁定", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "锁定期间，任何受管应用都无法打开，屏幕将被强制锁定。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(15, 25, 45, 60).forEach { minutes ->
                                    TextButton(onClick = { vm.startFocus(minutes) }) {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("$minutes 分钟")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("受管应用", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { vm.quickAddShortVideoApps() }) {
                        Text("一键添加常见短视频应用")
                    }
                }
            }

            if (guarded.isEmpty()) {
                item {
                    Card {
                        Text(
                            "还没有添加受管应用。\n点击右下角 + 选择要管理的应用，为它们设定每日使用限额。",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            items(guarded, key = { it.packageName }) { app ->
                GuardedAppCard(
                    app = app,
                    usedMs = home.usageByPackage[app.packageName] ?: 0L,
                    onLimitChange = { vm.updateLimit(app, it) },
                    onToggle = { vm.toggleEnabled(app) },
                    onRemove = { vm.removeGuardedApp(app) }
                )
            }
        }
    }
}

@Composable
private fun GuardedAppCard(
    app: GuardedAppEntity,
    usedMs: Long,
    onLimitChange: (Int) -> Unit,
    onToggle: () -> Unit,
    onRemove: () -> Unit
) {
    val limitMs = app.dailyLimitMinutes * 60_000L
    val ratio = if (limitMs > 0) (usedMs.toFloat() / limitMs).coerceIn(0f, 1f) else 0f
    val over = usedMs >= limitMs && limitMs > 0

    Card {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(app.appName, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = when {
                            over -> "已超限（${UsageStatsHelper.formatDuration(usedMs)}）"
                            else -> "已用 ${UsageStatsHelper.formatDuration(usedMs)} / 限额 ${app.dailyLimitMinutes} 分钟"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (over) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = app.enabled, onCheckedChange = { onToggle() })
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = "移除")
                }
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { ratio },
                modifier = Modifier.fillMaxWidth(),
                color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            Text("每日限额：${app.dailyLimitMinutes} 分钟", style = MaterialTheme.typography.bodySmall)
            var pendingLimit by remember(app.dailyLimitMinutes) {
                mutableIntStateOf(app.dailyLimitMinutes)
            }
            Slider(
                value = pendingLimit.toFloat(),
                // 只在松手时写库，拖动过程中每次滑动都写会拖垮 UI
                onValueChange = { pendingLimit = it.toInt().coerceAtLeast(5) },
                onValueChangeFinished = { onLimitChange(pendingLimit) },
                valueRange = 5f..240f,
                enabled = app.enabled
            )
        }
    }
}
