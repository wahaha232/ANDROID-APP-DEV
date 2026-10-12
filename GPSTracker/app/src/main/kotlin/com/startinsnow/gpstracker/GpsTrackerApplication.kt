package com.startinsnow.gpstracker

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.startinsnow.gpstracker.data.db.AppDatabase
import com.startinsnow.gpstracker.data.prefs.SettingsRepository
import com.startinsnow.gpstracker.data.repository.TrackRepository
import kotlinx.coroutines.launch

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
        // 舊版以明文存放的 GitHub Token 轉成加密格式（失敗不影響啟動）。
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { settingsRepository.migrateLegacySecrets() }
        }
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
