// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/data/mapper/LocationMapper.kt
package com.wahaha232.weatherforecast.data.mapper

import com.wahaha232.weatherforecast.data.remote.dto.ReverseGeocodingResponseDto
import com.wahaha232.weatherforecast.domain.model.City
import kotlin.math.roundToLong

/**
 * 將 Nominatim 反向地理編碼結果組成台灣慣用的「縣市 + 鄉鎮市區 + 村里」顯示格式，
 * 例如「新北市新莊區中信里」。若部分層級缺漏（例如非台灣地區），則自動退回較粗略的層級。
 */
fun ReverseGeocodingResponseDto.toDomainCity(latitude: Double, longitude: Double): City {
    val addr = address

    // 縣市層級：city > county > state
    val cityLevel = addr?.city ?: addr?.county ?: addr?.state

    // 鄉鎮市區層級：city_district > suburb > town
    val districtLevel = addr?.cityDistrict ?: addr?.suburb ?: addr?.town

    // 村里/鄰里層級：village > neighbourhood > quarter
    val villageLevel = addr?.village ?: addr?.neighbourhood ?: addr?.quarter

    val displayName = listOfNotNull(cityLevel, districtLevel, villageLevel)
        .distinct()
        .joinToString("")
        .ifBlank { addr?.village ?: addr?.suburb ?: displayName ?: "目前位置" }

    // 以座標組成穩定且不與遠端搜尋/最愛清單 id 衝突的負數 id（GPS 定位城市專用命名空間）
    val stableId = -((latitude * 10000).roundToLong() * 100000L + (longitude * 10000).roundToLong().mod(100000L))

    return City(
        id = stableId,
        name = displayName,
        admin1 = cityLevel,
        country = addr?.country,
        countryCode = addr?.countryCode?.uppercase(),
        latitude = latitude,
        longitude = longitude,
        timezone = null,
        isFavorite = false
    )
}
