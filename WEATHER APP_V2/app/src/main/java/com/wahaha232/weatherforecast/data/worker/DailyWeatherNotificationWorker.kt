// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/data/worker/DailyWeatherNotificationWorker.kt
package com.wahaha232.weatherforecast.data.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wahaha232.weatherforecast.R
import com.wahaha232.weatherforecast.WeatherApplication
import com.wahaha232.weatherforecast.domain.model.City
import com.wahaha232.weatherforecast.domain.model.TemperatureUnit
import com.wahaha232.weatherforecast.domain.model.UnitFormatter
import com.wahaha232.weatherforecast.domain.util.Resource
import kotlinx.coroutines.flow.first

/**
 * 每日天氣通知背景工作。取最愛清單第一個城市（若無則使用台北市預設座標）取得目前天氣，
 * 組成通知內容並發送。由 [com.wahaha232.weatherforecast.data.worker.WeatherNotificationScheduler] 排程。
 */
class DailyWeatherNotificationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as WeatherApplication).container

        val favoriteCities = container.getFavoriteCitiesUseCase().first()
        val targetCity = favoriteCities.firstOrNull() ?: DEFAULT_CITY

        val forecastResult = container.getWeatherForecastUseCase(targetCity)
        if (forecastResult !is Resource.Success) return Result.retry()

        val current = forecastResult.data.current
        val temp = UnitFormatter.formatTemperature(current.temperature, TemperatureUnit.CELSIUS)
        val content = "${targetCity.fullDisplayName} 現在 $temp，${current.conditionType.displayNameZh}"

        showNotification(content)
        return Result.success()
    }

    private fun showNotification(content: String) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "每日天氣提醒",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("每日天氣")
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val WORK_NAME = "daily_weather_notification_work"
        private const val CHANNEL_ID = "daily_weather_channel"
        private const val NOTIFICATION_ID = 1001

        private val DEFAULT_CITY = City(
            id = 1668341,
            name = "台北市",
            admin1 = "台灣",
            country = "台灣",
            countryCode = "TW",
            latitude = 25.033,
            longitude = 121.5654,
            timezone = "Asia/Taipei"
        )
    }
}
