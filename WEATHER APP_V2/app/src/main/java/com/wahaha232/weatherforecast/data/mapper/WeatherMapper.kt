// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/data/mapper/WeatherMapper.kt
package com.wahaha232.weatherforecast.data.mapper

import com.wahaha232.weatherforecast.data.local.FavoriteCityEntity
import com.wahaha232.weatherforecast.data.remote.dto.AirQualityResponseDto
import com.wahaha232.weatherforecast.data.remote.dto.GeocodingResultDto
import com.wahaha232.weatherforecast.data.remote.dto.WeatherForecastResponseDto
import com.wahaha232.weatherforecast.domain.model.AirQuality
import com.wahaha232.weatherforecast.domain.model.City
import com.wahaha232.weatherforecast.domain.model.CurrentWeather
import com.wahaha232.weatherforecast.domain.model.DailyForecastItem
import com.wahaha232.weatherforecast.domain.model.HourlyForecastItem
import com.wahaha232.weatherforecast.domain.model.WeatherConditionType
import com.wahaha232.weatherforecast.domain.model.WeatherForecast

/**
 * Data 層唯一負責「外部資料格式 -> Domain 模型」轉換的地方，
 * 讓 Repository 實作只專注在資料存取邏輯，轉換規則集中管理方便維護。
 */

fun GeocodingResultDto.toDomain(isFavorite: Boolean = false): City = City(
    id = id,
    name = name,
    admin1 = admin1,
    country = country,
    countryCode = countryCode,
    latitude = latitude,
    longitude = longitude,
    timezone = timezone,
    isFavorite = isFavorite
)

fun FavoriteCityEntity.toDomain(): City = City(
    id = id,
    name = name,
    admin1 = admin1,
    country = country,
    countryCode = countryCode,
    latitude = latitude,
    longitude = longitude,
    timezone = timezone,
    isFavorite = true
)

fun City.toFavoriteEntity(addedAtEpochMillis: Long): FavoriteCityEntity = FavoriteCityEntity(
    id = id,
    name = name,
    admin1 = admin1,
    country = country,
    countryCode = countryCode,
    latitude = latitude,
    longitude = longitude,
    timezone = timezone,
    addedAtEpochMillis = addedAtEpochMillis
)

fun AirQualityResponseDto.toDomain(): AirQuality? {
    val c = current ?: return null
    return AirQuality(
        usAqi = c.usAqi,
        pm2_5 = c.pm2_5,
        pm10 = c.pm10,
        carbonMonoxide = c.carbonMonoxide,
        nitrogenDioxide = c.nitrogenDioxide,
        sulphurDioxide = c.sulphurDioxide,
        ozone = c.ozone
    )
}

/**
 * 將 Open-Meteo forecast 回應轉換為完整的 Domain WeatherForecast。
 * 「目前時刻」的紫外線指數 Open-Meteo 並未提供於 current 區塊，
 * 因此改由 hourly.uv_index 依當下時間對應的小時索引取得，較為可靠。
 */
fun WeatherForecastResponseDto.toDomain(
    city: City,
    airQuality: AirQuality?,
    nowEpochMillis: Long
): WeatherForecast {
    val currentHourIndex = hourly.time.indexOfFirst { it == current.time }.let {
        if (it >= 0) it else 0
    }
    val currentUvIndex = hourly.uvIndex.getOrNull(currentHourIndex) ?: 0.0

    val currentWeather = CurrentWeather(
        temperature = current.temperature2m,
        apparentTemperature = current.apparentTemperature,
        relativeHumidity = current.relativeHumidity2m,
        windSpeedKmh = current.windSpeed10m,
        windDirectionDeg = current.windDirection10m,
        windGustsKmh = current.windGusts10m,
        pressureHpa = current.surfacePressure,
        uvIndex = currentUvIndex,
        isDay = current.isDay == 1,
        conditionType = WeatherConditionType.fromWmoCode(current.weatherCode)
    )

    // 從目前小時開始，往後取 24 筆逐時預報
    val hourlyStartIndex = currentHourIndex.coerceIn(0, (hourly.time.size - 1).coerceAtLeast(0))
    val hourlyEndIndex = (hourlyStartIndex + 24).coerceAtMost(hourly.time.size)
    val hourlyItems = (hourlyStartIndex until hourlyEndIndex).map { i ->
        HourlyForecastItem(
            timeIso = hourly.time[i],
            temperature = hourly.temperature2m.getOrElse(i) { 0.0 },
            precipitationProbability = hourly.precipitationProbability.getOrElse(i) { 0 },
            conditionType = WeatherConditionType.fromWmoCode(hourly.weatherCode.getOrElse(i) { 0 })
        )
    }

    val dailyItems = daily.time.indices.map { i ->
        DailyForecastItem(
            dateIso = daily.time[i],
            temperatureMax = daily.temperature2mMax.getOrElse(i) { 0.0 },
            temperatureMin = daily.temperature2mMin.getOrElse(i) { 0.0 },
            precipitationProbabilityMax = daily.precipitationProbabilityMax.getOrElse(i) { 0 },
            windSpeedMaxKmh = daily.windSpeed10mMax.getOrElse(i) { 0.0 },
            windDirectionDominantDeg = daily.windDirection10mDominant.getOrElse(i) { 0 },
            sunriseIso = daily.sunrise.getOrNull(i),
            sunsetIso = daily.sunset.getOrNull(i),
            uvIndexMax = daily.uvIndexMax.getOrElse(i) { 0.0 },
            conditionType = WeatherConditionType.fromWmoCode(daily.weatherCode.getOrElse(i) { 0 })
        )
    }

    return WeatherForecast(
        city = city.copy(timezone = timezone ?: city.timezone),
        current = currentWeather,
        hourly = hourlyItems,
        daily = dailyItems,
        airQuality = airQuality,
        lastUpdatedEpochMillis = nowEpochMillis
    )
}
