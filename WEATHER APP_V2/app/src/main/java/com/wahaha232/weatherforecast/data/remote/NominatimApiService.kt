// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/data/remote/NominatimApiService.kt
package com.wahaha232.weatherforecast.data.remote

import com.wahaha232.weatherforecast.data.remote.dto.ReverseGeocodingResponseDto
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/**
 * OpenStreetMap Nominatim 反向地理編碼 API（免費、無需 API Key）。
 * 使用政策要求請求需帶有可識別的 User-Agent，且限制每秒最多 1 次請求，僅適合輕量個人使用情境。
 * Base URL: https://nominatim.openstreetmap.org/
 */
interface NominatimApiService {

    @GET("reverse")
    suspend fun reverseGeocode(
        @Query("lat") latitude: Double,
        @Query("lon") longitude: Double,
        @Query("format") format: String = "jsonv2",
        @Query("addressdetails") addressDetails: Int = 1,
        @Query("accept-language") acceptLanguage: String = "zh-TW",
        @Header("User-Agent") userAgent: String = "WeatherForecastV2-AndroidApp/2.0"
    ): ReverseGeocodingResponseDto

    companion object {
        const val BASE_URL = "https://nominatim.openstreetmap.org/"
    }
}
