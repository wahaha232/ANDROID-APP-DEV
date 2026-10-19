package com.startinsnow.healthtracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter fun fromType(v: RecordType): String = v.name
    @TypeConverter fun toType(v: String): RecordType = RecordType.valueOf(v)
    @TypeConverter fun fromSource(v: RecordSource): String = v.name
    @TypeConverter fun toSource(v: String): RecordSource = RecordSource.valueOf(v)
    @TypeConverter fun fromSync(v: SyncState): String = v.name
    @TypeConverter fun toSync(v: String): SyncState = SyncState.valueOf(v)
}

@Database(entities = [HealthRecord::class], version = 2, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun healthRecordDao(): HealthRecordDao

    companion object {
        /** v2：新增 photoPath（OCR 原始照片路徑）。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE health_records ADD COLUMN photoPath TEXT")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "health_tracker.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
