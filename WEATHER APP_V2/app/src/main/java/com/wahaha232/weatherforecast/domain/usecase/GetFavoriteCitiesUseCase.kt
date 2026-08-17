// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/domain/usecase/GetFavoriteCitiesUseCase.kt
package com.wahaha232.weatherforecast.domain.usecase

import com.wahaha232.weatherforecast.domain.model.City
import com.wahaha232.weatherforecast.domain.repository.CityRepository
import kotlinx.coroutines.flow.Flow

/**
 * 觀察本地收藏城市清單（Room Flow，資料變動時自動推播）。
 */
class GetFavoriteCitiesUseCase(
    private val cityRepository: CityRepository
) {
    operator fun invoke(): Flow<List<City>> = cityRepository.observeFavoriteCities()
}
