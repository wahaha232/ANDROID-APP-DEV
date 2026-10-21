package com.startinsnow.healthtracker.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.startinsnow.healthtracker.BuildConfig
import com.startinsnow.healthtracker.HealthTrackerApp
import com.startinsnow.healthtracker.MainActivity
import com.startinsnow.healthtracker.R
import com.startinsnow.healthtracker.cloud.CloudErrorKind
import com.startinsnow.healthtracker.cloud.CloudException
import com.startinsnow.healthtracker.reminder.ReminderScheduler
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** 檢查間隔的選項（小時）；0 = 只手動檢查。 */
object UpdateIntervals {
    val OPTIONS = listOf(0, 6, 12, 24, 72, 168)

    fun label(hours: Int) = when (hours) {
        0 -> "僅手動"
        6 -> "每 6 小時"
        12 -> "每 12 小時"
        24 -> "每天"
        72 -> "每 3 天"
        168 -> "每週"
        else -> "每 $hours 小時"
    }

    fun sanitize(hours: Int) = if (hours in OPTIONS) hours else 0
}

/** 收到通知後要把畫面帶到「設定 > 關於」的訊號。 */
object UpdateNav {
    var openUpdate by mutableStateOf(false)
    const val EXTRA = "open_update"
}

/** 自動檢查的紀錄（SharedPreferences），讓設定頁看得到上次檢查的結果與已下載的新版。 */
object UpdateState {
    private const val PREFS = "update_state"

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun downloadedCode(c: Context) = p(c).getInt("dl_code", 0)
    fun downloadedName(c: Context) = p(c).getString("dl_name", "").orEmpty()
    fun notifiedCode(c: Context) = p(c).getInt("notified_code", 0)
    fun lastCheck(c: Context): Pair<Long, String>? =
        p(c).getString("last_check", null)?.split('|', limit = 2)?.takeIf { it.size == 2 }?.let { it[0].toLong() to it[1] }

    fun setDownloaded(c: Context, code: Int, name: String) = p(c).edit().putInt("dl_code", code).putString("dl_name", name).apply()
    fun setNotified(c: Context, code: Int) = p(c).edit().putInt("notified_code", code).apply()
    fun setLastCheck(c: Context, message: String) = p(c).edit().putString("last_check", "${System.currentTimeMillis()}|$message").apply()
}

object UpdateLogicAuto {
    /** 已下載的檔案要比目前安裝的版本新、而且檔案還在，才算「可以安裝」。 */
    fun pendingInstall(downloadedCode: Int, fileExists: Boolean, currentCode: Int) = downloadedCode > currentCode && fileExists

    /** 同一個新版本只通知一次。 */
    fun shouldNotify(latestCode: Int, notifiedCode: Int, currentCode: Int) = latestCode > currentCode && latestCode != notifiedCode

    /** 只有版本號變了或檔案不在才需要（重新）下載。 */
    fun shouldDownload(latestCode: Int, downloadedCode: Int, fileExists: Boolean) = latestCode != downloadedCode || !fileExists
}

/**
 * 自動檢查更新：依設定的間隔在背景（只在 Wi‑Fi 等不計量網路）檢查雲端有沒有新版，有就先下載並通知你。
 * Android 不允許 App 靜默安裝，所以最後的安裝仍要你點通知回到 App、再在系統畫面確認。
 */
object UpdateScheduler {
    const val WORK = "app-update-check"
    const val CHANNEL = "app_update"

    fun apply(context: Context, intervalHours: Int, policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE) {
        val wm = WorkManager.getInstance(context)
        val hours = UpdateIntervals.sanitize(intervalHours)
        if (hours == 0) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(hours.toLong(), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK, policy, request)
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "App 更新", NotificationManager.IMPORTANCE_DEFAULT))
    }

    @Suppress("MissingPermission")
    fun notifyDownloaded(context: Context, versionName: String) {
        if (!ReminderScheduler.hasNotificationPermission(context)) return
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).putExtra(UpdateNav.EXTRA, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("血糖記錄有新版本")
            .setContentText("$versionName 已下載，點一下回到 App 安裝")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(1002, n)
    }
}

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as HealthTrackerApp
        if (app.settings.settings.first().updateIntervalHours <= 0) return Result.success()
        val engine = app.cloud.engine ?: return Result.success()
        if (engine.currentSession() == null) {
            UpdateState.setLastCheck(applicationContext, "尚未登入雲端")
            return Result.success()
        }
        val updater = AppUpdater(engine, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
        return try {
            val info = updater.fetchLatest()
            val current = BuildConfig.VERSION_CODE
            if (info == null) {
                UpdateState.setLastCheck(applicationContext, "雲端還沒有放新版本")
            } else if (!UpdateLogic.isNewer(info, current)) {
                UpdateState.setLastCheck(applicationContext, "已是最新版（${BuildConfig.VERSION_NAME}）")
            } else {
                val file = AppUpdater.apkFile(applicationContext)
                if (UpdateLogicAuto.shouldDownload(info.versionCode, UpdateState.downloadedCode(applicationContext), file.isFile)) {
                    updater.download(info, file) { _, _ -> }
                    UpdateState.setDownloaded(applicationContext, info.versionCode, info.versionName)
                }
                UpdateState.setLastCheck(applicationContext, "新版本 ${info.versionName} 已下載，等你安裝")
                if (UpdateLogicAuto.shouldNotify(info.versionCode, UpdateState.notifiedCode(applicationContext), current)) {
                    UpdateScheduler.notifyDownloaded(applicationContext, info.versionName)
                    UpdateState.setNotified(applicationContext, info.versionCode)
                }
            }
            Result.success()
        } catch (e: CloudException) {
            UpdateState.setLastCheck(applicationContext, e.message ?: "檢查失敗")
            if (e.kind == CloudErrorKind.NETWORK && runAttemptCount < 3) Result.retry() else Result.success()
        } catch (e: Exception) {
            UpdateState.setLastCheck(applicationContext, "檢查失敗：${e.message}")
            Result.success()
        }
    }
}
