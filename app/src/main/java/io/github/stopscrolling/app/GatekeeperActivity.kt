package io.github.stopscrolling.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import io.github.stopscrolling.app.data.AppDatabase
import io.github.stopscrolling.app.data.BlockEventEntity
import io.github.stopscrolling.app.data.UsageStatsHelper
import io.github.stopscrolling.app.ui.theme.StopScrollTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 守门拦截页：当受管应用超过当日限额 / 处于睡觉时段时全屏弹出。
 * 按返回键不会回到被拦截的应用；先看 5 秒呼吸动画再操作。
 */
class GatekeeperActivity : ComponentActivity() {

    private var targetPackage: String? = null
    private var targetLabel: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE)
        targetLabel = intent.getStringExtra(EXTRA_LABEL)
        val bedtime = intent.getBooleanExtra(EXTRA_BEDTIME, false)

        val pkg = targetPackage ?: run { finish(); return }

        // 关键修复：返回键回到桌面而不是被拦截的应用
        val backCallback = object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val canGrace = !bedtime && runCatching {
            executor.submit<Boolean> {
                kotlinx.coroutines.runBlocking {
                    AppDatabase.get(this@GatekeeperActivity).blockEventDao()
                        .countSince("GRACE:$pkg", UsageStatsHelper.startOfDay()) == 0
                }
            }.get()
        }.getOrDefault(false)

        val blockedToday = runCatching {
            executor.submit<Int> {
                kotlinx.coroutines.runBlocking {
                    AppDatabase.get(this@GatekeeperActivity).blockEventDao()
                        .countSince("BLOCKED", UsageStatsHelper.startOfDay())
                }
            }.get()
        }.getOrDefault(0)
        executor.shutdown()

        setContent {
            StopScrollTheme {
                GatekeeperScreen(
                    appName = targetLabel ?: pkg,
                    usedToday = UsageStatsHelper.usageOf(this, pkg),
                    isBedtime = bedtime,
                    blockedToday = blockedToday,
                    canGrace = canGrace,
                    onLeave = { goHome() },
                    onGrace = {
                        grantGrace(pkg)
                        finish()
                    }
                )
            }
        }
    }

    private fun grantGrace(pkg: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.get(this@GatekeeperActivity)
            val now = System.currentTimeMillis()
            db.guardedAppDao().setGraceUntil(pkg, now + GRACE_MS)
            db.blockEventDao().insert(
                BlockEventEntity(packageName = pkg, timestamp = now, kind = "GRACE:$pkg")
            )
        }
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )
        finish()
    }

    companion object {
        private const val EXTRA_PACKAGE = "extra_package"
        private const val EXTRA_LABEL = "extra_label"
        private const val EXTRA_BEDTIME = "extra_bedtime"
        private const val GRACE_MS = 5 * 60_000L

        fun launch(context: Context, packageName: String, appLabel: String, bedtime: Boolean = false) {
            context.startActivity(
                Intent(context, GatekeeperActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra(EXTRA_PACKAGE, packageName)
                    putExtra(EXTRA_LABEL, appLabel)
                    putExtra(EXTRA_BEDTIME, bedtime)
                }
            )
        }
    }
}

private val QUOTES = listOf(
    "屏幕之外，世界也在等你。",
    "深呼吸，把注意力还给生活。",
    "真正的休息，不是换个屏幕继续刷。",
    "时间花在哪，人生就在哪。",
    "抬起头，看看今天的天空。",
    "你值得比信息流更好的东西。",
    "放下手机五分钟，什么都不会错过。",
    "先去做一件小事，回头再说。"
)

@Composable
private fun GatekeeperScreen(
    appName: String,
    usedToday: Long,
    isBedtime: Boolean,
    blockedToday: Int,
    canGrace: Boolean,
    onLeave: () -> Unit,
    onGrace: () -> Unit
) {
    // 5 秒冷静倒计时
    var cooldown by remember { mutableIntStateOf(5) }
    LaunchedEffect(Unit) {
        while (cooldown > 0) {
            delay(1000)
            cooldown--
        }
    }

    val quote = remember { QUOTES[Random.nextInt(QUOTES.size)] }

    val transition = rememberInfiniteTransition(label = "breath")
    val scale by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(2400), RepeatMode.Reverse),
        label = "breathScale"
    )
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(2400), RepeatMode.Reverse),
        label = "breathAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101418)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
        ) {
            // 呼吸圆
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    }
                    .background(Color(0xFF7BC4A2), CircleShape)
            )

            Spacer(Modifier.height(40.dp))
            Text(
                text = if (isBedtime) "现在是睡觉时段，该休息了"
                else "你今天已经刷了 ${UsageStatsHelper.formatDuration(usedToday)} 的「$appName」",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (isBedtime) "睡觉时段内，受管应用已全部拦截（无宽限）" else quote,
                color = Color(0xFF9AA6B2),
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )
            if (!isBedtime && blockedToday > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "今天已经被拦下 $blockedToday 次",
                    color = Color(0xFF5A6672),
                    fontSize = 13.sp
                )
            }
            Spacer(Modifier.height(48.dp))

            if (cooldown > 0) {
                Text(
                    text = "$cooldown",
                    color = Color(0xFF5A6672),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Button(
                    onClick = onLeave,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = MaterialTheme.shapes.large,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7BC4A2))
                ) {
                    Text(
                        if (isBedtime) "去睡觉" else "放下手机",
                        color = Color(0xFF101418),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (canGrace) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onGrace) {
                        Text("再刷 5 分钟（今日仅此一次）", color = Color(0xFF6B7682), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
