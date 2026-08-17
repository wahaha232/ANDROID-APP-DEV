// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/domain/model/City.kt
package com.wahaha232.weatherforecast.domain.model

/**
 * 城市 Domain 模型，同時用於「搜尋結果」與「最愛城市清單」。
 * id 使用 Open-Meteo Geocoding API 回傳的 location id；GPS 定位取得的城市則以座標組成穩定 id。
 */
data class City(
    val id: Long,
    val name: String,
    val admin1: String?,
    val country: String?,
    val countryCode: String?,
    val latitude: Double,
    val longitude: Double,
    val timezone: String?,
    val isFavorite: Boolean = false
) {
    /** 完整顯示名稱，例如「台北市, 台灣」 */
    val fullDisplayName: String
        get() = listOfNotNull(name, admin1?.takeIf { it != name }, country)
            .distinct()
            .joinToString(", ")
}
