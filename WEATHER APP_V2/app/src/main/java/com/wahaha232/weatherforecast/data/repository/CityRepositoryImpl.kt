// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/data/repository/CityRepositoryImpl.kt
package com.wahaha232.weatherforecast.data.repository

import com.wahaha232.weatherforecast.data.local.FavoriteCityDao
import com.wahaha232.weatherforecast.data.mapper.toDomain
import com.wahaha232.weatherforecast.data.mapper.toFavoriteEntity
import com.wahaha232.weatherforecast.data.remote.OpenMeteoGeocodingApiService
import com.wahaha232.weatherforecast.domain.model.City
import com.wahaha232.weatherforecast.domain.repository.CityRepository
import com.wahaha232.weatherforecast.domain.util.Resource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.IOException

class CityRepositoryImpl(
    private val geocodingApi: OpenMeteoGeocodingApiService,
    private val favoriteCityDao: FavoriteCityDao
) : CityRepository {

    override suspend fun searchCities(query: String): Resource<List<City>> {
        return try {
            val response = geocodingApi.searchCities(name = query)
            val results = response.results.orEmpty().map { it.toDomain() }
            Resource.Success(results)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Resource.Error("網路連線失敗，無法搜尋城市", e)
        } catch (e: Exception) {
            Resource.Error(e.message ?: "搜尋城市時發生未知錯誤", e)
        }
    }

    override fun observeFavoriteCities(): Flow<List<City>> {
        return favoriteCityDao.observeAll().map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun addFavoriteCity(city: City) {
        favoriteCityDao.insert(city.toFavoriteEntity(System.currentTimeMillis()))
    }

    override suspend fun removeFavoriteCity(cityId: Long) {
        favoriteCityDao.deleteById(cityId)
    }

    override suspend fun isFavorite(cityId: Long): Boolean {
        return favoriteCityDao.isFavorite(cityId)
    }
}
