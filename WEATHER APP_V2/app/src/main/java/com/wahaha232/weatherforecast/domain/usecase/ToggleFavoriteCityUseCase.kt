// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/domain/usecase/ToggleFavoriteCityUseCase.kt
package com.wahaha232.weatherforecast.domain.usecase

import com.wahaha232.weatherforecast.domain.model.City
import com.wahaha232.weatherforecast.domain.repository.CityRepository

/**
 * 切換城市的最愛狀態：已收藏則移除，未收藏則新增。
 */
class ToggleFavoriteCityUseCase(
    private val cityRepository: CityRepository
) {
    suspend operator fun invoke(city: City) {
        if (cityRepository.isFavorite(city.id)) {
            cityRepository.removeFavoriteCity(city.id)
        } else {
            cityRepository.addFavoriteCity(city)
        }
    }
}
