package com.startinsnow.healthtracker

import android.app.Application
import com.startinsnow.healthtracker.cloud.CloudSync
import com.startinsnow.healthtracker.data.db.AppDatabase
import com.startinsnow.healthtracker.data.prefs.UserSettingsRepository
import com.startinsnow.healthtracker.data.repository.HealthRecordRepository
import com.startinsnow.healthtracker.reminder.ReminderScheduler
import com.startinsnow.healthtracker.sync.SyncScheduler

class HealthTrackerApp : Application() {
    companion object {
        lateinit var appContext: android.content.Context
            private set
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        SyncScheduler.ensureScheduled(this)
        ReminderScheduler.ensureChannel(this)
    }

    val database: AppDatabase by lazy { AppDatabase.create(this) }
    val records: HealthRecordRepository by lazy { HealthRecordRepository(database.healthRecordDao(), database) }
    val settings: UserSettingsRepository by lazy { UserSettingsRepository(this) }
    val cloud: CloudSync by lazy { CloudSync(this, database.healthRecordDao()) }
}
