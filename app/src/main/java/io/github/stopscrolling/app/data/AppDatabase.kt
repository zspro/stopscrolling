package io.github.stopscrolling.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface GuardedAppDao {
    @Query("SELECT * FROM guarded_apps ORDER BY appName")
    fun observeAll(): Flow<List<GuardedAppEntity>>

    @Query("SELECT * FROM guarded_apps WHERE packageName = :pkg")
    suspend fun find(pkg: String): GuardedAppEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(app: GuardedAppEntity)

    @Query("DELETE FROM guarded_apps WHERE packageName = :pkg")
    suspend fun delete(pkg: String)

    @Query("UPDATE guarded_apps SET graceUntil = :until WHERE packageName = :pkg")
    suspend fun setGraceUntil(pkg: String, until: Long)
}

@Dao
interface BlockEventDao {
    @Insert
    suspend fun insert(event: BlockEventEntity)

    @Query("SELECT COUNT(*) FROM block_events WHERE kind = :kind AND timestamp >= :since")
    fun observeCountSince(kind: String, since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM block_events WHERE kind = :kind AND timestamp >= :since")
    suspend fun countSince(kind: String, since: Long): Int
}

@Database(
    entities = [GuardedAppEntity::class, BlockEventEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun guardedAppDao(): GuardedAppDao
    abstract fun blockEventDao(): BlockEventDao

    companion object {
        /** v1 -> v2: 受管应用表增加宽限期字段 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE guarded_apps_new (" +
                        "packageName TEXT NOT NULL PRIMARY KEY, " +
                        "appName TEXT NOT NULL, " +
                        "dailyLimitMinutes INTEGER NOT NULL, " +
                        "enabled INTEGER NOT NULL, " +
                        "graceUntil INTEGER NOT NULL DEFAULT 0)"
                )
                db.execSQL(
                    "INSERT INTO guarded_apps_new (packageName, appName, dailyLimitMinutes, enabled, graceUntil) " +
                        "SELECT packageName, appName, dailyLimitMinutes, enabled, 0 FROM guarded_apps"
                )
                db.execSQL("DROP TABLE guarded_apps")
                db.execSQL("ALTER TABLE guarded_apps_new RENAME TO guarded_apps")
            }
        }

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "stopscrolling.db"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
