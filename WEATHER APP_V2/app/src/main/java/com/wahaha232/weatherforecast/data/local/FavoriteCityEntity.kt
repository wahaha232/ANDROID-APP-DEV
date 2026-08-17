// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/data/local/FavoriteCityEntity.kt
package com.wahaha232.weatherforecast.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 最愛城市的 Room 資料表結構。
 */
@Entity(tableName = "favorite_cities")
data class FavoriteCityEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val admin1: String?,
    val country: String?,
    val countryCode: String?,
    val latitude: Double,
    val longitude: Double,
    val timezone: String?,
    val addedAtEpochMillis: Long
)
