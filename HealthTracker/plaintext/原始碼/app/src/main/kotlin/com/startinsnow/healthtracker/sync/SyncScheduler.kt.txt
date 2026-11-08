package com.startinsnow.healthtracker.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * 背景同步排程：
 * - 每 6 小時檢查一次，有待上傳的資料才同步（沒有就不連網）。
 * - 每晚 20:00 完整同步（先拉後推）。用「做完再排下一次」的單次工作，避免週期工作逐日漂移。
 * - 失敗（網路、伺服器暫時錯誤）由 WorkManager 指數退避重試。
 */
object SyncScheduler {
    const val DAILY_WORK = "daily-sync"
    const val CHECK_WORK = "periodic-check"
    const val KEY_ONLY_IF_PENDING = "only_if_pending"
    const val DAILY_HOUR = 20
    const val BACKOFF_MINUTES = 15L

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** 距離下一個 hour:00 還有多久（已過今天的時間就算明天）。 */
    fun delayUntilNext(hour: Int, now: ZonedDateTime): Duration {
        var next = now.withHour(hour).withMinute(0).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }

    /** App 啟動時呼叫：已排好就保留，不重複排。 */
    fun ensureScheduled(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            CHECK_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(network)
                .setInputData(workDataOf(KEY_ONLY_IF_PENDING to true))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build(),
        )
        scheduleDaily(context, ExistingWorkPolicy.KEEP)
    }

    /** 排下一次 20:00 同步。工作做完（非重試）時以 APPEND_OR_REPLACE 接續下一天。 */
    fun scheduleDaily(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE) {
        val delay = delayUntilNext(DAILY_HOUR, ZonedDateTime.now(ZoneId.systemDefault()))
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .addTag(DAILY_WORK)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(DAILY_WORK, policy, request)
    }
}
