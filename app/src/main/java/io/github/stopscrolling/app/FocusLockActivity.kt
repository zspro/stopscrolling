package io.github.stopscrolling.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.stopscrolling.app.data.SettingsStore
import io.github.stopscrolling.app.ui.theme.StopScrollTheme
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * 专注锁定页：专注时段内打开受管应用会被弹到这里。
 * 显示剩余时间，倒计时结束后自动关闭。
 */
class FocusLockActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StopScrollTheme {
                val store = SettingsStore(applicationContext)
                // -1 表示尚未读到真实值，避免初始 0 误触发自动关闭
                val endAt by store.focusEndAt
                    .collectAsStateWithLifecycle(initialValue = -1L)
                var seenPositive by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf(false)
                }
                // 倒计时归零后自动关闭并撤掉通知
                LaunchedEffect(endAt) {
                    when {
                        endAt == -1L -> Unit
                        endAt <= 0L -> {
                            if (seenPositive) {
                                io.github.stopscrolling.app.util.Notifications.cancelFocus(applicationContext)
                                finish()
                            }
                        }
                        endAt > System.currentTimeMillis() -> seenPositive = true
                        else -> {
                            io.github.stopscrolling.app.util.Notifications.cancelFocus(applicationContext)
                            finish()
                        }
                    }
                }
                val remainMs = (endAt - System.currentTimeMillis()).coerceAtLeast(0)
                FocusLockScreen(remainMs)
            }
        }
    }

    companion object {
        fun launch(context: Context) {
            context.startActivity(
                Intent(context, FocusLockActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            )
        }
    }
}

@Composable
private fun FocusLockScreen(remainMs: Long) {
    // 每秒重组一次以驱动倒计时
    var tick by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis())
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick = System.currentTimeMillis()
        }
    }
    val shown = remainMs.coerceAtLeast(0L) - (System.currentTimeMillis() - tick)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(shown + 59_999)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(shown + 999) % 60

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .background(Color(0xFF5B8DB8), CircleShape)
            )
            Spacer(Modifier.height(40.dp))
            Text("专注锁定中", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                text = String.format("%02d:%02d", minutes, seconds),
                color = Color(0xFF5B8DB8),
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "这段时间，去做更重要的事吧。",
                color = Color(0xFF9AA6B2),
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
