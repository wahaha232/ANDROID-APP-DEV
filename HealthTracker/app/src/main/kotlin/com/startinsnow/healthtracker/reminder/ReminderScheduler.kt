package com.startinsnow.healthtracker.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.startinsnow.healthtracker.HealthTrackerApp
import com.startinsnow.healthtracker.MainActivity
import com.startinsnow.healthtracker.R
import com.startinsnow.healthtracker.data.db.HealthRecord
import com.startinsnow.healthtracker.data.db.RecordType
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object ReminderLogic {
    const val MAX_TIMES = 4

    /** 距離下一個 minuteOfDay（當天第幾分鐘）還有多久；已過就算明天。 */
    fun delayUntilNext(minuteOfDay: Int, now: ZonedDateTime): Duration {
        var next = now.withHour(minuteOfDay / 60).withMinute(minuteOfDay % 60).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }

    /** 要不要發提醒：設定「今天已記錄就不提醒」且今天已有血糖紀錄時不發。 */
    fun shouldNotify(records: List<HealthRecord>, skipIfRecorded: Boolean, now: ZonedDateTime): Boolean {
        if (!skipIfRecorded) return true
        val start = now.toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli()
        val end = start + 86_400_000L
        return records.none { it.type == RecordType.BLOOD_GLUCOSE && it.deletedAt == null && it.measuredAt in start until end }
    }

    fun label(minuteOfDay: Int) = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
}

/** 每個提醒時間一個單次工作，做完再排隔天（不會逐日漂移）。關閉提醒就全部取消。 */
object ReminderScheduler {
    const val TAG = "reminder"
    const val KEY_MINUTE = "minute"
    const val CHANNEL = "reminder"

    private fun workName(minute: Int) = "reminder-$minute"

    fun apply(context: Context, enabled: Boolean, times: List<Int>) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        if (!enabled) return
        times.forEach { schedule(context, it, ExistingWorkPolicy.REPLACE) }
    }

    fun schedule(context: Context, minute: Int, policy: ExistingWorkPolicy) {
        val delay = ReminderLogic.delayUntilNext(minute, ZonedDateTime.now(ZoneId.systemDefault()))
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_MINUTE to minute))
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(minute), policy, request)
    }

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "量測提醒", NotificationManager.IMPORTANCE_DEFAULT))
    }

    @Suppress("MissingPermission")
    fun notifyNow(context: Context) {
        if (!hasNotificationPermission(context)) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("血糖記錄")
            .setContentText("該記錄血糖了")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(1001, n)
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val minute = inputData.getInt(ReminderScheduler.KEY_MINUTE, -1)
        val app = applicationContext as HealthTrackerApp
        val s = app.settings.settings.first()
        // 設定已關閉或這個時間已被移除：不發也不再排
        if (!s.reminderEnabled || minute !in s.reminderTimes) return Result.success()
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        if (ReminderLogic.shouldNotify(app.records.observeAll().first(), s.reminderSkipIfRecorded, now)) {
            ReminderScheduler.notifyNow(applicationContext)
        }
        ReminderScheduler.schedule(applicationContext, minute, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }
}
