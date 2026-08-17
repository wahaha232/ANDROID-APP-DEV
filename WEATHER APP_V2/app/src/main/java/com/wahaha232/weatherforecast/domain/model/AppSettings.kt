// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/domain/model/AppSettings.kt
package com.wahaha232.weatherforecast.domain.model

enum class TemperatureUnit { CELSIUS, FAHRENHEIT }
enum class WindSpeedUnit { KMH, MPH, MS }
enum class PressureUnit { MBAR, MMHG, INHG }
enum class VisibilityUnit { KM, MI }
enum class PrecipitationUnit { MM, IN }

/**
 * App 全域偏好設定，對應側邊選單「功能」區塊的各項單位與開關。
 * 由 DataStore Preferences 持久化保存。
 */
data class AppSettings(
    val temperatureUnit: TemperatureUnit = TemperatureUnit.CELSIUS,
    val windSpeedUnit: WindSpeedUnit = WindSpeedUnit.KMH,
    val pressureUnit: PressureUnit = PressureUnit.MBAR,
    val visibilityUnit: VisibilityUnit = VisibilityUnit.KM,
    val precipitationUnit: PrecipitationUnit = PrecipitationUnit.MM,
    val dailyWeatherNotificationEnabled: Boolean = false,
    val weatherBackgroundEnabled: Boolean = true,
    val showNightInfo: Boolean = true
)
