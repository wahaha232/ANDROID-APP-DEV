// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/data/remote/dto/WeatherForecastDto.kt
package com.wahaha232.weatherforecast.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Open-Meteo `/v1/forecast` 端點的完整回應結構。
 * 欄位名稱對應 API 的 snake_case JSON key，透過 @SerialName 對應到 Kotlin 慣例命名。
 */
@Serializable
data class WeatherForecastResponseDto(
    val latitude: Double,
    val longitude: Double,
    val timezone: String? = null,
    @SerialName("current") val current: CurrentWeatherDto,
    @SerialName("hourly") val hourly: HourlyWeatherDto,
    @SerialName("daily") val daily: DailyWeatherDto
)

@Serializable
data class CurrentWeatherDto(
    val time: String,
    @SerialName("temperature_2m") val temperature2m: Double,
    @SerialName("relative_humidity_2m") val relativeHumidity2m: Int,
    @SerialName("apparent_temperature") val apparentTemperature: Double,
    @SerialName("is_day") val isDay: Int,
    @SerialName("weather_code") val weatherCode: Int,
    @SerialName("wind_speed_10m") val windSpeed10m: Double,
    @SerialName("wind_direction_10m") val windDirection10m: Int = 0,
    @SerialName("wind_gusts_10m") val windGusts10m: Double = 0.0,
    @SerialName("surface_pressure") val surfacePressure: Double = 1013.0
)

@Serializable
data class HourlyWeatherDto(
    val time: List<String>,
    @SerialName("temperature_2m") val temperature2m: List<Double>,
    @SerialName("precipitation_probability") val precipitationProbability: List<Int> = emptyList(),
    @SerialName("weather_code") val weatherCode: List<Int>,
    @SerialName("uv_index") val uvIndex: List<Double> = emptyList()
)

@Serializable
data class DailyWeatherDto(
    val time: List<String>,
    @SerialName("weather_code") val weatherCode: List<Int>,
    @SerialName("temperature_2m_max") val temperature2mMax: List<Double>,
    @SerialName("temperature_2m_min") val temperature2mMin: List<Double>,
    @SerialName("precipitation_probability_max") val precipitationProbabilityMax: List<Int> = emptyList(),
    @SerialName("wind_speed_10m_max") val windSpeed10mMax: List<Double> = emptyList(),
    @SerialName("wind_direction_10m_dominant") val windDirection10mDominant: List<Int> = emptyList(),
    @SerialName("sunrise") val sunrise: List<String> = emptyList(),
    @SerialName("sunset") val sunset: List<String> = emptyList(),
    @SerialName("uv_index_max") val uvIndexMax: List<Double> = emptyList()
)

/**
 * Open-Meteo `/v1/air-quality` 端點回應結構。
 */
@Serializable
data class AirQualityResponseDto(
    @SerialName("current") val current: AirQualityCurrentDto? = null
)

@Serializable
data class AirQualityCurrentDto(
    @SerialName("us_aqi") val usAqi: Int? = null,
    @SerialName("pm2_5") val pm2_5: Double? = null,
    @SerialName("pm10") val pm10: Double? = null,
    @SerialName("carbon_monoxide") val carbonMonoxide: Double? = null,
    @SerialName("nitrogen_dioxide") val nitrogenDioxide: Double? = null,
    @SerialName("sulphur_dioxide") val sulphurDioxide: Double? = null,
    @SerialName("ozone") val ozone: Double? = null
)
