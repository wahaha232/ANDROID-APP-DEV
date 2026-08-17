// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/domain/usecase/GetCurrentLocationUseCase.kt
package com.wahaha232.weatherforecast.domain.usecase

import com.wahaha232.weatherforecast.domain.model.Coordinates
import com.wahaha232.weatherforecast.domain.repository.LocationRepository
import com.wahaha232.weatherforecast.domain.util.Resource

/**
 * 取得裝置目前 GPS 座標。呼叫前呼叫端（ViewModel/UI）必須確保已取得定位權限。
 */
class GetCurrentLocationUseCase(
    private val locationRepository: LocationRepository
) {
    suspend operator fun invoke(): Resource<Coordinates> {
        return locationRepository.getCurrentCoordinates()
    }
}
