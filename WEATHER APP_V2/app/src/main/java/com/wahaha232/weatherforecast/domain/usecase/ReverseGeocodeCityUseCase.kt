// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/domain/usecase/ReverseGeocodeCityUseCase.kt
package com.wahaha232.weatherforecast.domain.usecase

import com.wahaha232.weatherforecast.domain.model.City
import com.wahaha232.weatherforecast.domain.repository.CityRepository
import com.wahaha232.weatherforecast.domain.util.Resource

/**
 * 將 GPS 座標反查為精確到村里等級的城市名稱（例如「新北市新莊區中信里」）。
 */
class ReverseGeocodeCityUseCase(
    private val cityRepository: CityRepository
) {
    suspend operator fun invoke(latitude: Double, longitude: Double): Resource<City> {
        return cityRepository.reverseGeocodeCity(latitude, longitude)
    }
}
