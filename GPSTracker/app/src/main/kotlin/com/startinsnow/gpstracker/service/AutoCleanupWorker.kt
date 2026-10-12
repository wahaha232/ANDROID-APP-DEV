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
        val settings = app.settingsRepository.settings.first()
        val retentionDays = settings.dataRetentionDays
        if (retentionDays <= 0) return Result.success()

        val cutoff = System.currentTimeMillis() - retentionDays * 24L * 60 * 60 * 1000
        // 於 SQL 端篩選（不把整張表載入記憶體），再交由 Repository 刪除（含本機檔案）。
        // 已開啟雲端同步時，只清理「已成功備份」的軌跡：尚未（或上傳失敗）備份的永遠不自動刪除。
        // 未開啟雲端同步時，使用者自行設定的保留天數即為意圖，照設定清理（設定頁有警語）。
        val expiredTrackIds = if (settings.driveSyncEnabled) {
            app.trackRepository.getUploadedFinishedTrackIdsBefore(TrackStatus.FINISHED, cutoff)
        } else {
            app.trackRepository.getFinishedTrackIdsBefore(TrackStatus.FINISHED, cutoff)
        }
        // 單筆刪除失敗不應中斷整批。
        expiredTrackIds.forEach { id -> runCatching { app.trackRepository.deleteTrack(id) } }
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
