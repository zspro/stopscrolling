package io.github.stopscrolling.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import io.github.stopscrolling.app.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 防改时间作弊：监听系统时间/时区变更。
 *
 * 限额按「自然日」重置，如果直接把系统时间拨到明天，就能绕过当日限额。
 * 本接收器用 [SystemClock.elapsedRealtime]（不受系统时间影响）与墙钟差值
 * 做交叉校验：墙钟跳变但 [Settings.Global.BOOT_COUNT] 未变 = 手脚不干净，
 * 记录为可疑事件并在首页提示。
 */
class TimeChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_TIME_CHANGED &&
            action != Intent.ACTION_TIMEZONE_CHANGED &&
            action != Intent.ACTION_DATE_CHANGED
        ) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = SettingsStore(context)
                val nowWall = System.currentTimeMillis()
                val nowElapsed = SystemClock.elapsedRealtime()
                val nowBoot = try {
                    Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
                } catch (_: Exception) {
                    -1
                }

                val prevWall = store.lastWallClock.first()
                val prevElapsed = store.lastElapsedRealtime.first()
                val prevBoot = store.lastBootCount.first()

                if (prevWall in 1 until nowWall && prevBoot == nowBoot && prevElapsed > 0) {
                    // 期间未重启：墙钟增量应≈elapsedRealtime 增量，偏差大即为可疑
                    val wallDelta = nowWall - prevWall
                    val elapsedDelta = nowElapsed - prevElapsed
                    val drift = wallDelta - elapsedDelta
                    if (drift > DRIFT_THRESHOLD_MS || drift < -DRIFT_THRESHOLD_MS) {
                        store.recordTimeTamper(nowWall)
                    }
                }

                store.updateClockBaseline(nowWall, nowElapsed, nowBoot)
            } catch (_: Exception) {
                // 记录失败不影响系统广播
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** 墙钟与单调时钟偏差超过 2 分钟视为可疑 */
        private const val DRIFT_THRESHOLD_MS = 2 * 60_000L
    }
}
