package com.startinsnow.gpstracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TrackEntity::class,
        TrackPointEntity::class,
        PhotoEntity::class,
        MovementSegmentEntity::class,
        GpsOutageEntity::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun trackPointDao(): TrackPointDao
    abstract fun photoDao(): PhotoDao
    abstract fun movementSegmentDao(): MovementSegmentDao
    abstract fun gpsOutageDao(): GpsOutageDao

    companion object {
        /** v1 → v2：tracks 新增 stepSource（步數來源標記）。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN stepSource TEXT NOT NULL DEFAULT 'SENSOR'")
            }
        }

        fun build(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "gps_tracker.db"
        )
            // Crash Recovery（規格 57）：每個 Location Point 即時 flush 進 Room 的 WAL，
            // 即使 App 被系統回收，已提交的資料不會遺失。
            .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(MIGRATION_1_2)
            .build()
    }
}
