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
}
