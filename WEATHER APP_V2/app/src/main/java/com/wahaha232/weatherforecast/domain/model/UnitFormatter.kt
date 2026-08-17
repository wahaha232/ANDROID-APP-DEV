// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/domain/model/UnitFormatter.kt
package com.wahaha232.weatherforecast.domain.model

import kotlin.math.roundToInt

/**
 * 依使用者選擇的單位偏好，將 API 原始數值（攝氏、km/h、hPa、km、mm）轉換成顯示用的數值與單位標籤。
 * 純函式，方便單元測試，Presentation 層只需呼叫這裡即可，不需要在 Composable 裡分散寫轉換公式。
 */
object UnitFormatter {

    fun formatTemperature(celsius: Double, unit: TemperatureUnit): String = when (unit) {
        TemperatureUnit.CELSIUS -> "${celsius.roundToInt()}°"
        TemperatureUnit.FAHRENHEIT -> "${(celsius * 9 / 5 + 32).roundToInt()}°"
    }

    fun formatWindSpeed(kmh: Double, unit: WindSpeedUnit): Pair<String, String> = when (unit) {
        WindSpeedUnit.KMH -> "%.1f".format(kmh) to "km/h"
        WindSpeedUnit.MPH -> "%.1f".format(kmh * 0.621371) to "mph"
        WindSpeedUnit.MS -> "%.1f".format(kmh / 3.6) to "m/s"
    }

    fun formatPressure(hpa: Double, unit: PressureUnit): Pair<String, String> = when (unit) {
        PressureUnit.MBAR -> "%.1f".format(hpa) to "mbar"
        PressureUnit.MMHG -> "%.0f".format(hpa * 0.750062) to "mmHg"
        PressureUnit.INHG -> "%.2f".format(hpa * 0.02953) to "inHg"
    }

    fun formatVisibility(km: Double, unit: VisibilityUnit): Pair<String, String> = when (unit) {
        VisibilityUnit.KM -> "%.1f".format(km) to "km"
        VisibilityUnit.MI -> "%.1f".format(km * 0.621371) to "mi"
    }

    fun formatPrecipitation(mm: Double, unit: PrecipitationUnit): Pair<String, String> = when (unit) {
        PrecipitationUnit.MM -> "%.1f".format(mm) to "mm"
        PrecipitationUnit.IN -> "%.2f".format(mm * 0.0393701) to "in"
    }
}
