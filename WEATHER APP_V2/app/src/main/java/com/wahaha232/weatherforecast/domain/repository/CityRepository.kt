// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/domain/repository/CityRepository.kt
package com.wahaha232.weatherforecast.domain.repository

import com.wahaha232.weatherforecast.domain.model.City
import com.wahaha232.weatherforecast.domain.util.Resource
import kotlinx.coroutines.flow.Flow

/**
 * 城市搜尋（遠端 Geocoding API）與最愛城市（本地 Room 資料庫）的統一存取介面。
 */
interface CityRepository {
    /** 依關鍵字搜尋全球城市（遠端） */
    suspend fun searchCities(query: String): Resource<List<City>>

    /** 觀察本地收藏的城市清單，Room Flow 會在資料變動時自動推送新結果 */
    fun observeFavoriteCities(): Flow<List<City>>

    /** 新增城市到最愛清單 */
    suspend fun addFavoriteCity(city: City)

    /** 從最愛清單移除城市 */
    suspend fun removeFavoriteCity(cityId: Long)

    /** 檢查指定城市是否已收藏 */
    suspend fun isFavorite(cityId: Long): Boolean
}
