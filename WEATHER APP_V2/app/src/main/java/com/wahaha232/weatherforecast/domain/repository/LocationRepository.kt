// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/domain/repository/LocationRepository.kt
package com.wahaha232.weatherforecast.domain.repository

import com.wahaha232.weatherforecast.domain.model.Coordinates
import com.wahaha232.weatherforecast.domain.util.Resource

/**
 * 裝置定位抽象介面。呼叫端必須自行確保已取得執行期定位權限，
 * 實作（LocationRepositoryImpl）內部會再次防禦性檢查權限，避免 SecurityException。
 */
interface LocationRepository {
    suspend fun getCurrentCoordinates(): Resource<Coordinates>
}
