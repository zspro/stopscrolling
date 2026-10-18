package io.github.stopscrolling.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** 全局设置：专注模式、睡觉时段 */
class SettingsStore(private val context: Context) {

    private val FOCUS_END = longPreferencesKey("focus_end_at")
    private val BEDTIME_ENABLED = booleanPreferencesKey("bedtime_enabled")
    private val BEDTIME_START = intPreferencesKey("bedtime_start_hour")
    private val BEDTIME_END = intPreferencesKey("bedtime_end_hour")
    private val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")

    // ── 防改时间作弊基线（TimeChangeReceiver 维护）──
    private val TAMPER_COUNT = intPreferencesKey("time_tamper_count")
    private val LAST_TAMPER_AT = longPreferencesKey("time_last_tamper_at")
    private val TAMPER_ACK_AT = longPreferencesKey("time_tamper_ack_at")
    private val LAST_WALL_CLOCK = longPreferencesKey("clock_baseline_wall")
    private val LAST_ELAPSED = longPreferencesKey("clock_baseline_elapsed")
    private val LAST_BOOT_COUNT = intPreferencesKey("clock_baseline_boot")

    /** 专注模式结束时间戳，0 表示未开启 */
    val focusEndAt: Flow<Long> = context.dataStore.data.map { it[FOCUS_END] ?: 0L }

    /** 睡觉时段：期间受管应用一律拦截（无宽限） */
    val bedtimeEnabled: Flow<Boolean> = context.dataStore.data.map { it[BEDTIME_ENABLED] ?: false }
    val bedtimeStartHour: Flow<Int> = context.dataStore.data.map { it[BEDTIME_START] ?: 23 }
    val bedtimeEndHour: Flow<Int> = context.dataStore.data.map { it[BEDTIME_END] ?: 7 }

    /** 首次启动引导是否已完成 */
    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { it[ONBOARDING_DONE] ?: false }

    suspend fun setOnboardingDone() {
        context.dataStore.edit { it[ONBOARDING_DONE] = true }
    }

    suspend fun startFocus(durationMinutes: Int) {
        context.dataStore.edit {
            it[FOCUS_END] = System.currentTimeMillis() + durationMinutes * 60_000L
        }
    }

    suspend fun stopFocus() {
        context.dataStore.edit { it[FOCUS_END] = 0L }
    }

    suspend fun setBedtime(enabled: Boolean, startHour: Int, endHour: Int) {
        context.dataStore.edit {
            it[BEDTIME_ENABLED] = enabled
            it[BEDTIME_START] = startHour
            it[BEDTIME_END] = endHour
        }
    }

    // ── 防改时间作弊 ──

    /** 检测到的可疑改时间次数 */
    val timeTamperCount: Flow<Int> = context.dataStore.data.map { it[TAMPER_COUNT] ?: 0 }

    /** 最近一次可疑改动时间戳，0 表示从未 */
    val lastTamperAt: Flow<Long> = context.dataStore.data.map { it[LAST_TAMPER_AT] ?: 0L }

    /** 用户已确认查看的时间戳，0 表示有未读告警 */
    val tamperAckAt: Flow<Long> = context.dataStore.data.map { it[TAMPER_ACK_AT] ?: 0L }

    val lastWallClock: Flow<Long> = context.dataStore.data.map { it[LAST_WALL_CLOCK] ?: 0L }
    val lastElapsedRealtime: Flow<Long> = context.dataStore.data.map { it[LAST_ELAPSED] ?: 0L }
    val lastBootCount: Flow<Int> = context.dataStore.data.map { it[LAST_BOOT_COUNT] ?: -1 }

    suspend fun recordTimeTamper(atWallClock: Long) {
        context.dataStore.edit {
            it[TAMPER_COUNT] = (it[TAMPER_COUNT] ?: 0) + 1
            it[LAST_TAMPER_AT] = atWallClock
        }
    }

    /** 用户点掉告警横幅 */
    suspend fun ackTimeTamper() {
        context.dataStore.edit { it[TAMPER_ACK_AT] = System.currentTimeMillis() }
    }

    /** 刷新时钟基线，供下次对比 */
    suspend fun updateClockBaseline(wall: Long, elapsed: Long, bootCount: Int) {
        context.dataStore.edit {
            it[LAST_WALL_CLOCK] = wall
            it[LAST_ELAPSED] = elapsed
            it[LAST_BOOT_COUNT] = bootCount
        }
    }
}
