package io.github.stopscrolling.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 受管应用：用户为它设定了每日使用限额 */
@Entity(tableName = "guarded_apps")
data class GuardedAppEntity(
    @PrimaryKey val packageName: String,
    val appName: String,
    val dailyLimitMinutes: Int,
    val enabled: Boolean = true,
    /** 宽限期截止时间戳（「再刷 5 分钟」生效中），0 表示无 */
    val graceUntil: Long = 0
)

/** 拦截事件日志 */
@Entity(tableName = "block_events")
data class BlockEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val timestamp: Long,
    val kind: String // BLOCKED / GRACE:<pkg> / WARN:<pkg> / FOCUS_LOCK
)
