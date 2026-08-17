// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/domain/model/Coordinates.kt
package com.wahaha232.weatherforecast.domain.model

/**
 * 純 Kotlin 座標模型，Domain 層不依賴 Android Location API，
 * 讓定位邏輯與具體實作（FusedLocationProviderClient）解耦。
 */
data class Coordinates(
    val latitude: Double,
    val longitude: Double
)
