// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/domain/usecase/SearchCitiesUseCase.kt
package com.wahaha232.weatherforecast.domain.usecase

import com.wahaha232.weatherforecast.domain.model.City
import com.wahaha232.weatherforecast.domain.repository.CityRepository
import com.wahaha232.weatherforecast.domain.util.Resource

/**
 * 依關鍵字搜尋全球城市。少於 2 個字元時直接回傳空清單，避免無意義的 API 呼叫。
 */
class SearchCitiesUseCase(
    private val cityRepository: CityRepository
) {
    suspend operator fun invoke(query: String): Resource<List<City>> {
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            return Resource.Success(emptyList())
        }
        return cityRepository.searchCities(trimmed)
    }
}
