// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/presentation/weather/components/WeatherIconMapper.kt
package com.wahaha232.weatherforecast.presentation.weather.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.ModeNight
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.ui.graphics.vector.ImageVector
import com.wahaha232.weatherforecast.domain.model.WeatherConditionType

/**
 * 依天氣狀況型別（+ 是否為白天）決定要顯示的 Material Icon。
 * Domain 只描述「是什麼天氣」，圖示對照放在 Presentation 層才符合 Clean Architecture 分層原則。
 */
fun WeatherConditionType.toIcon(isDay: Boolean = true): ImageVector = when (this) {
    WeatherConditionType.CLEAR_SKY -> if (isDay) Icons.Filled.WbSunny else Icons.Filled.ModeNight
    WeatherConditionType.MAINLY_CLEAR -> if (isDay) Icons.Filled.WbTwilight else Icons.Filled.ModeNight
    WeatherConditionType.PARTLY_CLOUDY -> Icons.Filled.WbCloudy
    WeatherConditionType.OVERCAST -> Icons.Filled.Cloud
    WeatherConditionType.FOG, WeatherConditionType.DEPOSITING_RIME_FOG -> Icons.Filled.CloudQueue
    WeatherConditionType.DRIZZLE_LIGHT,
    WeatherConditionType.DRIZZLE_MODERATE,
    WeatherConditionType.DRIZZLE_DENSE,
    WeatherConditionType.FREEZING_DRIZZLE -> Icons.Filled.Grain
    WeatherConditionType.RAIN_SLIGHT,
    WeatherConditionType.RAIN_MODERATE,
    WeatherConditionType.RAIN_HEAVY,
    WeatherConditionType.FREEZING_RAIN,
    WeatherConditionType.RAIN_SHOWERS_SLIGHT,
    WeatherConditionType.RAIN_SHOWERS_MODERATE,
    WeatherConditionType.RAIN_SHOWERS_VIOLENT -> Icons.Filled.Umbrella
    WeatherConditionType.SNOW_SLIGHT,
    WeatherConditionType.SNOW_MODERATE,
    WeatherConditionType.SNOW_HEAVY,
    WeatherConditionType.SNOW_GRAINS,
    WeatherConditionType.SNOW_SHOWERS_SLIGHT,
    WeatherConditionType.SNOW_SHOWERS_HEAVY -> Icons.Filled.AcUnit
    WeatherConditionType.THUNDERSTORM,
    WeatherConditionType.THUNDERSTORM_HAIL_SLIGHT,
    WeatherConditionType.THUNDERSTORM_HAIL_HEAVY -> Icons.Filled.Thunderstorm
    WeatherConditionType.UNKNOWN -> Icons.Filled.Air
}

/** 依天氣狀況決定卡片主要強調色（用於溫度數字、圖示著色） */
fun WeatherConditionType.accentColorArgb(): Long = when (this) {
    WeatherConditionType.CLEAR_SKY, WeatherConditionType.MAINLY_CLEAR -> 0xFFFFB74D
    WeatherConditionType.PARTLY_CLOUDY, WeatherConditionType.OVERCAST -> 0xFF90A4AE
    WeatherConditionType.FOG, WeatherConditionType.DEPOSITING_RIME_FOG -> 0xFFB0BEC5
    WeatherConditionType.THUNDERSTORM,
    WeatherConditionType.THUNDERSTORM_HAIL_SLIGHT,
    WeatherConditionType.THUNDERSTORM_HAIL_HEAVY -> 0xFF9575CD
    WeatherConditionType.SNOW_SLIGHT,
    WeatherConditionType.SNOW_MODERATE,
    WeatherConditionType.SNOW_HEAVY,
    WeatherConditionType.SNOW_GRAINS,
    WeatherConditionType.SNOW_SHOWERS_SLIGHT,
    WeatherConditionType.SNOW_SHOWERS_HEAVY -> 0xFF81D4FA
    else -> 0xFF4FC3F7
}
