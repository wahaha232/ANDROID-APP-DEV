// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/domain/repository/WeatherRepository.kt
package com.wahaha232.weatherforecast.domain.repository

import com.wahaha232.weatherforecast.domain.model.City
import com.wahaha232.weatherforecast.domain.model.WeatherForecast
import com.wahaha232.weatherforecast.domain.util.Resource

/**
 * 天氣資料的 Repository 抽象介面，Domain 層只依賴此介面，
 * 實際的 Retrofit + Open-Meteo 串接細節由 data 層的 WeatherRepositoryImpl 負責。
 */
interface WeatherRepository {
    suspend fun getWeatherForecast(city: City): Resource<WeatherForecast>
}
