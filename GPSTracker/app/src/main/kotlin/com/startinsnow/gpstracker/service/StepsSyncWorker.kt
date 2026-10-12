package com.startinsnow.gpstracker.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.startinsnow.gpstracker.GpsTrackerApplication
import com.startinsnow.gpstracker.sync.DailyStepsSync
import java.util.concurrent.TimeUnit

/**
 * 定期把每天的步數（Health Connect）上傳到網頁儀表板的 steps.json。
 * 在背景讀取 Health Connect 需要「背景讀取」權限；任何失敗（未授權、沒網路、未設定 Token）都直接結束，
 * 下一輪再試，不重試也不影響其他功能。
 */
class StepsSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as GpsTrackerApplication
        DailyStepsSync.sync(app, days = DailyStepsSync.RECENT_DAYS)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "daily_steps_sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<StepsSyncWorker>(3, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
