// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/widget/WidgetIconMapper.kt
package com.wahaha232.weatherforecast.widget

import com.wahaha232.weatherforecast.R
import com.wahaha232.weatherforecast.domain.model.WeatherConditionType

/**
 * Glance 的 Image 元件需要 drawable 資源 ID（無法像 Compose Material Icons 那樣直接畫 ImageVector），
 * 因此小工具另外準備一套精簡的靜態向量圖示，依天氣狀況型別（+ 是否為白天）對應。
 */
fun WeatherConditionType.toWidgetIconRes(isDay: Boolean = true): Int = when (this) {
    WeatherConditionType.CLEAR_SKY -> if (isDay) R.drawable.ic_widget_sun else R.drawable.ic_widget_moon
    WeatherConditionType.MAINLY_CLEAR -> if (isDay) R.drawable.ic_widget_cloud_sun else R.drawable.ic_widget_moon
    WeatherConditionType.PARTLY_CLOUDY -> if (isDay) R.drawable.ic_widget_cloud_sun else R.drawable.ic_widget_cloud
    WeatherConditionType.OVERCAST -> R.drawable.ic_widget_cloud
    WeatherConditionType.FOG, WeatherConditionType.DEPOSITING_RIME_FOG -> R.drawable.ic_widget_fog
    WeatherConditionType.DRIZZLE_LIGHT,
    WeatherConditionType.DRIZZLE_MODERATE,
    WeatherConditionType.DRIZZLE_DENSE,
    WeatherConditionType.FREEZING_DRIZZLE,
    WeatherConditionType.RAIN_SLIGHT,
    WeatherConditionType.RAIN_MODERATE,
    WeatherConditionType.RAIN_HEAVY,
    WeatherConditionType.FREEZING_RAIN,
    WeatherConditionType.RAIN_SHOWERS_SLIGHT,
    WeatherConditionType.RAIN_SHOWERS_MODERATE,
    WeatherConditionType.RAIN_SHOWERS_VIOLENT -> R.drawable.ic_widget_rain
    WeatherConditionType.SNOW_SLIGHT,
    WeatherConditionType.SNOW_MODERATE,
    WeatherConditionType.SNOW_HEAVY,
    WeatherConditionType.SNOW_GRAINS,
    WeatherConditionType.SNOW_SHOWERS_SLIGHT,
    WeatherConditionType.SNOW_SHOWERS_HEAVY -> R.drawable.ic_widget_snow
    WeatherConditionType.THUNDERSTORM,
    WeatherConditionType.THUNDERSTORM_HAIL_SLIGHT,
    WeatherConditionType.THUNDERSTORM_HAIL_HEAVY -> R.drawable.ic_widget_thunder
    WeatherConditionType.UNKNOWN -> R.drawable.ic_widget_cloud
}
