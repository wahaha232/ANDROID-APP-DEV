package com.startinsnow.healthtracker

import android.app.Application
import com.startinsnow.healthtracker.data.db.AppDatabase
import com.startinsnow.healthtracker.data.prefs.UserSettingsRepository
import com.startinsnow.healthtracker.data.repository.HealthRecordRepository

class HealthTrackerApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.create(this) }
    val records: HealthRecordRepository by lazy { HealthRecordRepository(database.healthRecordDao()) }
    val settings: UserSettingsRepository by lazy { UserSettingsRepository(this) }
}
