package com.startinsnow.healthtracker.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.startinsnow.healthtracker.HealthTrackerApp

/** 背景同步工作：沒設定雲端或沒登入就直接結束；可重試的失敗最多重試 MAX_ATTEMPTS 次，之後等下一輪。 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val isDaily = tags.contains(SyncScheduler.DAILY_WORK)
        val engine = (applicationContext as HealthTrackerApp).cloud.engine
        val result = when {
            engine == null || engine.currentSession() == null -> null
            inputData.getBoolean(SyncScheduler.KEY_ONLY_IF_PENDING, false) && !engine.hasPending() -> null
            else -> engine.sync()
        }
        val retry = result != null && result.retryable && runAttemptCount < MAX_ATTEMPTS
        if (retry) return Result.retry()
        // 每晚 20:00 的工作在「最終結果」確定後排下一天（重試期間不排，避免重複）。
        if (isDaily) SyncScheduler.scheduleDaily(applicationContext)
        return Result.success()
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
    }
}
