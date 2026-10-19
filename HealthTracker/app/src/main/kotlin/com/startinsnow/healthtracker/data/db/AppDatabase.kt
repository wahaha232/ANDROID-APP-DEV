package com.startinsnow.healthtracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun fromType(v: RecordType): String = v.name
    @TypeConverter fun toType(v: String): RecordType = RecordType.valueOf(v)
    @TypeConverter fun fromSource(v: RecordSource): String = v.name
    @TypeConverter fun toSource(v: String): RecordSource = RecordSource.valueOf(v)
    @TypeConverter fun fromSync(v: SyncState): String = v.name
    @TypeConverter fun toSync(v: String): SyncState = SyncState.valueOf(v)
}

@Database(entities = [HealthRecord::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun healthRecordDao(): HealthRecordDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "health_tracker.db")
                .build()
    }
}
