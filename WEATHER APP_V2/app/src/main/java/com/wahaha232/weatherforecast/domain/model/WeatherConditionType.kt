// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/domain/model/WeatherConditionType.kt
package com.wahaha232.weatherforecast.domain.model

import kotlinx.serialization.Serializable

/**
 * WMO (World Meteorological Organization) Weather interpretation code 的型別安全對應。
 * Open-Meteo API 回傳的 weather_code 皆遵循此標準（0, 1, 2, 3, 45, 48, 51...）。
 * Domain 層只描述「是什麼天氣狀況」，實際圖示與顏色留給 Presentation 層決定，
 * 藉此保持 Domain 不依賴任何 Android/Compose API；標註 @Serializable 只是引入
 * kotlinx.serialization（純 Kotlin、無 Android 依賴）以便小工具能將狀態序列化保存。
 */
@Serializable
enum class WeatherConditionType(val displayNameZh: String) {
    CLEAR_SKY("晴朗"),
    MAINLY_CLEAR("大致晴朗"),
    PARTLY_CLOUDY("多雲"),
    OVERCAST("陰天"),
    FOG("有霧"),
    DEPOSITING_RIME_FOG("凍霧"),
    DRIZZLE_LIGHT("微量毛毛雨"),
    DRIZZLE_MODERATE("毛毛雨"),
    DRIZZLE_DENSE("密集毛毛雨"),
    FREEZING_DRIZZLE("凍雨"),
    RAIN_SLIGHT("小雨"),
    RAIN_MODERATE("中雨"),
    RAIN_HEAVY("大雨"),
    FREEZING_RAIN("凍雨"),
    SNOW_SLIGHT("小雪"),
    SNOW_MODERATE("中雪"),
    SNOW_HEAVY("大雪"),
    SNOW_GRAINS("雪粒"),
    RAIN_SHOWERS_SLIGHT("微弱陣雨"),
    RAIN_SHOWERS_MODERATE("中度陣雨"),
    RAIN_SHOWERS_VIOLENT("劇烈陣雨"),
    SNOW_SHOWERS_SLIGHT("微弱陣雪"),
    SNOW_SHOWERS_HEAVY("強烈陣雪"),
    THUNDERSTORM("雷雨"),
    THUNDERSTORM_HAIL_SLIGHT("雷雨伴有微量冰雹"),
    THUNDERSTORM_HAIL_HEAVY("劇烈雷雨伴有大冰雹"),
    UNKNOWN("未知天氣");

    companion object {
        /**
         * 依照 WMO weather_code 轉換成對應的天氣狀況型別。
         * 對照表參考：https://open-meteo.com/en/docs (WMO Weather interpretation codes)
         */
        fun fromWmoCode(code: Int): WeatherConditionType = when (code) {
            0 -> CLEAR_SKY
            1 -> MAINLY_CLEAR
            2 -> PARTLY_CLOUDY
            3 -> OVERCAST
            45 -> FOG
            48 -> DEPOSITING_RIME_FOG
            51 -> DRIZZLE_LIGHT
            53 -> DRIZZLE_MODERATE
            55 -> DRIZZLE_DENSE
            56, 57 -> FREEZING_DRIZZLE
            61 -> RAIN_SLIGHT
            63 -> RAIN_MODERATE
            65 -> RAIN_HEAVY
            66, 67 -> FREEZING_RAIN
            71 -> SNOW_SLIGHT
            73 -> SNOW_MODERATE
            75 -> SNOW_HEAVY
            77 -> SNOW_GRAINS
            80 -> RAIN_SHOWERS_SLIGHT
            81 -> RAIN_SHOWERS_MODERATE
            82 -> RAIN_SHOWERS_VIOLENT
            85 -> SNOW_SHOWERS_SLIGHT
            86 -> SNOW_SHOWERS_HEAVY
            95 -> THUNDERSTORM
            96 -> THUNDERSTORM_HAIL_SLIGHT
            99 -> THUNDERSTORM_HAIL_HEAVY
            else -> UNKNOWN
        }
    }
}
