// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/data/repository/WeatherRepositoryImpl.kt
package com.wahaha232.weatherforecast.data.repository

import com.wahaha232.weatherforecast.data.mapper.toDomain
import com.wahaha232.weatherforecast.data.remote.OpenMeteoAirQualityApiService
import com.wahaha232.weatherforecast.data.remote.OpenMeteoForecastApiService
import com.wahaha232.weatherforecast.domain.model.AirQuality
import com.wahaha232.weatherforecast.domain.model.City
import com.wahaha232.weatherforecast.domain.model.WeatherForecast
import com.wahaha232.weatherforecast.domain.repository.WeatherRepository
import com.wahaha232.weatherforecast.domain.util.Resource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.IOException

class WeatherRepositoryImpl(
    private val forecastApi: OpenMeteoForecastApiService,
    private val airQualityApi: OpenMeteoAirQualityApiService
) : WeatherRepository {

    override suspend fun getWeatherForecast(city: City): Resource<WeatherForecast> {
        return try {
            coroutineScope {
                val forecastDeferred = async {
                    forecastApi.getForecast(latitude = city.latitude, longitude = city.longitude)
                }
                // 空氣品質為輔助資訊，失敗不應影響主要天氣預報的顯示
                val airQualityDeferred = async {
                    runCatching {
                        airQualityApi.getAirQuality(latitude = city.latitude, longitude = city.longitude).toDomain()
                    }.getOrNull()
                }

                val forecastResponse = forecastDeferred.await()
                val airQuality: AirQuality? = airQualityDeferred.await()

                Resource.Success(forecastResponse.toDomain(city, airQuality, System.currentTimeMillis()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Resource.Error("網路連線失敗，請檢查網路狀態後重試", e)
        } catch (e: Exception) {
            Resource.Error(e.message ?: "取得天氣預報資料時發生未知錯誤", e)
        }
    }
}
