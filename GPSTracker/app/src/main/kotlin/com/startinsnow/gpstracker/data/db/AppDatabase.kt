package com.startinsnow.gpstracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        TrackEntity::class,
        TrackPointEntity::class,
        PhotoEntity::class,
        MovementSegmentEntity::class,
        GpsOutageEntity::class
    ],
    version = 1,
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
        fun build(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "gps_tracker.db"
        )
            // Crash Recovery（規格 57）：每個 Location Point 即時 flush 進 Room 的 WAL，
            // 即使 App 被系統回收，已提交的資料不會遺失。
            .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
            .build()
    }
}
