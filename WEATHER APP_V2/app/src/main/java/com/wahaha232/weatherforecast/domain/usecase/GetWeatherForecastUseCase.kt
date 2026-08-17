// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/domain/usecase/GetWeatherForecastUseCase.kt
package com.wahaha232.weatherforecast.domain.usecase

import com.wahaha232.weatherforecast.domain.model.City
import com.wahaha232.weatherforecast.domain.model.WeatherForecast
import com.wahaha232.weatherforecast.domain.repository.WeatherRepository
import com.wahaha232.weatherforecast.domain.util.Resource

/**
 * 取得指定城市完整天氣預報（現況 + 24 小時逐時 + 7 天每日）。
 */
class GetWeatherForecastUseCase(
    private val weatherRepository: WeatherRepository
) {
    suspend operator fun invoke(city: City): Resource<WeatherForecast> {
        return weatherRepository.getWeatherForecast(city)
    }
}
