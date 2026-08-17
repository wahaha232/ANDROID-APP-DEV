// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/data/remote/OpenMeteoApiService.kt
package com.wahaha232.weatherforecast.data.remote

import com.wahaha232.weatherforecast.data.remote.dto.AirQualityResponseDto
import com.wahaha232.weatherforecast.data.remote.dto.GeocodingResponseDto
import com.wahaha232.weatherforecast.data.remote.dto.WeatherForecastResponseDto
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Open-Meteo 天氣預報 API（免費、無需 API Key）。
 * Base URL: https://api.open-meteo.com/
 */
interface OpenMeteoForecastApiService {

    @GET("v1/forecast")
    suspend fun getForecast(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("current") current: String = CURRENT_FIELDS,
        @Query("hourly") hourly: String = HOURLY_FIELDS,
        @Query("daily") daily: String = DAILY_FIELDS,
        @Query("timezone") timezone: String = "auto",
        @Query("forecast_days") forecastDays: Int = 7
    ): WeatherForecastResponseDto

    companion object {
        const val BASE_URL = "https://api.open-meteo.com/"
        private const val CURRENT_FIELDS =
            "temperature_2m,relative_humidity_2m,apparent_temperature,is_day,weather_code," +
                "wind_speed_10m,wind_direction_10m,wind_gusts_10m,surface_pressure"
        private const val HOURLY_FIELDS =
            "temperature_2m,precipitation_probability,weather_code,uv_index"
        private const val DAILY_FIELDS =
            "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max," +
                "wind_speed_10m_max,wind_direction_10m_dominant,sunrise,sunset,uv_index_max"
    }
}

/**
 * Open-Meteo 空氣品質 API。
 * Base URL: https://air-quality-api.open-meteo.com/
 */
interface OpenMeteoAirQualityApiService {

    @GET("v1/air-quality")
    suspend fun getAirQuality(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("current") current: String = "us_aqi,pm10,pm2_5,carbon_monoxide,nitrogen_dioxide,sulphur_dioxide,ozone",
        @Query("timezone") timezone: String = "auto"
    ): AirQualityResponseDto

    companion object {
        const val BASE_URL = "https://air-quality-api.open-meteo.com/"
    }
}

/**
 * Open-Meteo 地理編碼（城市搜尋）API。
 * Base URL: https://geocoding-api.open-meteo.com/
 */
interface OpenMeteoGeocodingApiService {

    @GET("v1/search")
    suspend fun searchCities(
        @Query("name") name: String,
        @Query("count") count: Int = 10,
        @Query("language") language: String = "zh",
        @Query("format") format: String = "json"
    ): GeocodingResponseDto

    companion object {
        const val BASE_URL = "https://geocoding-api.open-meteo.com/"
    }
}
