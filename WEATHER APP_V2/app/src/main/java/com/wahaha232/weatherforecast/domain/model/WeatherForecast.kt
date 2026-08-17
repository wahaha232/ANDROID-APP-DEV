// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/domain/model/WeatherForecast.kt
package com.wahaha232.weatherforecast.domain.model

/**
 * 目前天氣狀況 Domain 模型。
 */
data class CurrentWeather(
    val temperature: Double,
    val apparentTemperature: Double,
    val relativeHumidity: Int,
    val windSpeedKmh: Double,
    val windDirectionDeg: Int,
    val windGustsKmh: Double,
    val pressureHpa: Double,
    val uvIndex: Double,
    val isDay: Boolean,
    val conditionType: WeatherConditionType
)

/**
 * 單一小時的逐時預報項目。
 */
data class HourlyForecastItem(
    val timeIso: String,
    val temperature: Double,
    val precipitationProbability: Int,
    val conditionType: WeatherConditionType
)

/**
 * 單一天的每日預報項目。
 */
data class DailyForecastItem(
    val dateIso: String,
    val temperatureMax: Double,
    val temperatureMin: Double,
    val precipitationProbabilityMax: Int,
    val windSpeedMaxKmh: Double,
    val windDirectionDominantDeg: Int,
    val sunriseIso: String?,
    val sunsetIso: String?,
    val uvIndexMax: Double,
    val conditionType: WeatherConditionType
)

/**
 * 空氣品質 Domain 模型（US AQI 標準）。
 */
data class AirQuality(
    val usAqi: Int?,
    val pm2_5: Double?,
    val pm10: Double?,
    val carbonMonoxide: Double?,
    val nitrogenDioxide: Double?,
    val sulphurDioxide: Double?,
    val ozone: Double?
)

/**
 * 聚合完整天氣預報結果：目前天氣 + 24 小時逐時 + 7 天每日 + 空氣品質，
 * 是 GetWeatherForecastUseCase 回傳給 Presentation 層的核心資料模型。
 */
data class WeatherForecast(
    val city: City,
    val current: CurrentWeather,
    val hourly: List<HourlyForecastItem>,
    val daily: List<DailyForecastItem>,
    val airQuality: AirQuality?,
    val lastUpdatedEpochMillis: Long
)
