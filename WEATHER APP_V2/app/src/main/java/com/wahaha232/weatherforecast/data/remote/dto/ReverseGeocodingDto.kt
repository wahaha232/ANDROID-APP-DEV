// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/data/remote/dto/ReverseGeocodingDto.kt
package com.wahaha232.weatherforecast.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Nominatim (OpenStreetMap) 反向地理編碼回應結構。
 * `address` 各欄位依國家/地區行政層級不同而有不同命名，
 * 台灣地址通常對應：city/county（縣市）> suburb/city_district（鄉鎮市區）> neighbourhood/village（村里）。
 */
@Serializable
data class ReverseGeocodingResponseDto(
    val lat: String? = null,
    val lon: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val address: ReverseGeocodingAddressDto? = null
)

@Serializable
data class ReverseGeocodingAddressDto(
    val country: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
    val state: String? = null,
    val county: String? = null,
    val city: String? = null,
    val town: String? = null,
    val suburb: String? = null,
    @SerialName("city_district") val cityDistrict: String? = null,
    val village: String? = null,
    val neighbourhood: String? = null,
    val quarter: String? = null
)
