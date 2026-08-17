// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/data/worker/WeatherNotificationScheduler.kt
package com.wahaha232.weatherforecast.data.worker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 依側邊選單「每日天氣」開關狀態，啟用或取消每日天氣通知的背景排程。
 */
object WeatherNotificationScheduler {

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<DailyWeatherNotificationWorker>(24, TimeUnit.HOURS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DailyWeatherNotificationWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(DailyWeatherNotificationWorker.WORK_NAME)
    }
}
