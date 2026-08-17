// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/widget/WeatherWidgetData.kt
package com.wahaha232.weatherforecast.widget

import com.wahaha232.weatherforecast.domain.model.WeatherConditionType
import com.wahaha232.weatherforecast.domain.model.WeatherForecast
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 小工具相關類別共用同一個 Json 實例，避免各處重複建立造成不必要的效能開銷。 */
val widgetJson = Json { ignoreUnknownKeys = true }

/**
 * 桌面小工具顯示用的精簡快照，序列化成 JSON 字串存在 Glance 的 Preferences state 裡。
 * 小工具執行在獨立的 RemoteViews host process，無法直接持有 ViewModel／Flow，
 * 因此每次天氣更新（App 內或小工具手動刷新）都會產生一份新的快照覆蓋舊的。
 */
@Serializable
data class WeatherWidgetData(
    val cityId: Long,
    val cityName: String,
    val latitude: Double,
    val longitude: Double,
    val currentTemp: Int,
    val tempMax: Int,
    val tempMin: Int,
    val conditionLabel: String,
    val conditionType: WeatherConditionType,
    val isDay: Boolean,
    val lastUpdatedEpochMillis: Long,
    val daily: List<WidgetDailyItem>
)

@Serializable
data class WidgetDailyItem(
    val weekdayLabel: String,
    val conditionType: WeatherConditionType,
    val tempMax: Int,
    val tempMin: Int
)

fun WeatherForecast.toWidgetData(): WeatherWidgetData {
    val today = daily.firstOrNull()
    return WeatherWidgetData(
        cityId = city.id,
        cityName = city.name,
        latitude = city.latitude,
        longitude = city.longitude,
        currentTemp = current.temperature.toInt(),
        tempMax = today?.temperatureMax?.toInt() ?: current.temperature.toInt(),
        tempMin = today?.temperatureMin?.toInt() ?: current.temperature.toInt(),
        conditionLabel = current.conditionType.displayNameZh,
        conditionType = current.conditionType,
        isDay = current.isDay,
        lastUpdatedEpochMillis = lastUpdatedEpochMillis,
        daily = daily.drop(1).take(4).map { day ->
            WidgetDailyItem(
                weekdayLabel = day.dateIso,
                conditionType = day.conditionType,
                tempMax = day.temperatureMax.toInt(),
                tempMin = day.temperatureMin.toInt()
            )
        }
    )
}
