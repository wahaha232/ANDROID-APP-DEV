package com.startinsnow.gpstracker

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.startinsnow.gpstracker.data.db.AppDatabase
import com.startinsnow.gpstracker.data.prefs.SettingsRepository
import com.startinsnow.gpstracker.data.repository.TrackRepository

class GpsTrackerApplication : Application() {

    val database: AppDatabase by lazy { AppDatabase.build(this) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val trackRepository: TrackRepository by lazy {
        TrackRepository(
            trackDao = database.trackDao(),
            trackPointDao = database.trackPointDao(),
            photoDao = database.photoDao(),
            movementSegmentDao = database.movementSegmentDao(),
            gpsOutageDao = database.gpsOutageDao(),
            filesDir = filesDir,
            photoRootDir = getExternalFilesDir("photos")
        )
    }

    override fun onCreate() {
        super.onCreate()
        org.maplibre.android.MapLibre.getInstance(this)
        createNotificationChannels()
        com.startinsnow.gpstracker.service.AutoCleanupWorker.schedule(this)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                RECORDING_CHANNEL_ID,
                getString(R.string.notif_channel_recording),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val RECORDING_CHANNEL_ID = "gps_tracker_recording"
    }
}
