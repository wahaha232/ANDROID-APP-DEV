package com.startinsnow.gpstracker.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.startinsnow.gpstracker.GpsTrackerApplication
import com.startinsnow.gpstracker.core.model.TrackStatus
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * 對應規格「60. Auto Cleanup」。預設不啟用（Data Retention Days = 0 代表永久保留），
 * 只有使用者主動設定保留天數 > 0 時才會刪除超過天數的已完成 Track。
 */
class AutoCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as GpsTrackerApplication
        val settings = app.settingsRepository.settings
        val retentionDays = settings.first().dataRetentionDays
        if (retentionDays <= 0) return Result.success()

        val cutoff = System.currentTimeMillis() - retentionDays * 24L * 60 * 60 * 1000
        val tracks = app.trackRepository.observeTracks().first()
        tracks.filter { it.status == TrackStatus.FINISHED && (it.endTimeMs ?: it.startTimeMs) < cutoff }
            .forEach { app.trackRepository.deleteTrack(it.trackId) }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "auto_cleanup"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AutoCleanupWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
