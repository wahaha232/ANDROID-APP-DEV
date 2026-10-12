package com.startinsnow.gpstracker.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.startinsnow.gpstracker.GpsTrackerApplication
import com.startinsnow.gpstracker.core.model.TrackStatus
import com.startinsnow.gpstracker.sync.StepResolver
import com.startinsnow.gpstracker.sync.WebDashboardSync
import java.util.concurrent.TimeUnit

/**
 * 錄製結束後延後重讀 Health Connect 步數。
 *
 * Zepp Life 要等手環同步到手機、再寫入 Health Connect，常比「結束錄製」晚數分鐘到數小時，
 * 結束當下讀不到會退回感測器；這裡在結束後 15 分鐘與 3 小時各補讀一次，讀到資料就更新該筆軌跡。
 * 任何失敗（未授權、Health Connect 不可用）都視為完成，不重試，也不影響其他資料。
 */
class StepResyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val trackId = inputData.getString(KEY_TRACK_ID) ?: return Result.success()
        val app = applicationContext as GpsTrackerApplication
        val track = app.trackRepository.getTrack(trackId) ?: return Result.success()
        if (track.status != TrackStatus.FINISHED) return Result.success()
        val endMs = track.endTimeMs ?: return Result.success()

        // Google Fit 為主；目前讀不到（手環還沒同步）就等下一次補讀。
        val resolved = StepResolver(applicationContext).resolveHealthConnect(track.startTimeMs, endMs).getOrNull()
            ?: return Result.success()
        val steps = resolved.steps
        val tag = resolved.sourceTag
        val changed = steps != track.stepCount || tag != track.stepSource
        if (changed) app.trackRepository.updateSteps(trackId, steps, tag)

        // 步數有更新（或上次上傳失敗重試）時，重傳到網頁儀表板，讓網站顯示補同步後的步數。
        if (changed || runAttemptCount > 0) {
            val outcome = WebDashboardSync.uploadIfConfigured(app, trackId, onlyIfAlreadyOnWeb = true)
            val needRetry = outcome.isFailure || outcome.getOrNull() == WebDashboardSync.Outcome.SKIPPED_WIFI_ONLY
            if (needRetry && runAttemptCount < MAX_RETRIES) return Result.retry()
        }
        return Result.success()
    }

    companion object {
        private const val KEY_TRACK_ID = "trackId"
        private val DELAYS_MINUTES = listOf(15L, 180L)
        private const val MAX_RETRIES = 4

        fun schedule(context: Context, trackId: String) {
            val wm = WorkManager.getInstance(context)
            DELAYS_MINUTES.forEach { delay ->
                val request = OneTimeWorkRequestBuilder<StepResyncWorker>()
                    .setInitialDelay(delay, TimeUnit.MINUTES)
                    .setInputData(workDataOf(KEY_TRACK_ID to trackId))
                    .build()
                wm.enqueueUniqueWork("step_resync_${trackId}_$delay", ExistingWorkPolicy.REPLACE, request)
            }
        }
    }
}
