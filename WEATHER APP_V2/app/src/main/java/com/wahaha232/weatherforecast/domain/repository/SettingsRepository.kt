// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/domain/repository/SettingsRepository.kt
package com.wahaha232.weatherforecast.domain.repository

import com.wahaha232.weatherforecast.domain.model.AppSettings
import kotlinx.coroutines.flow.Flow

/**
 * App 偏好設定的存取介面，實作由 data 層以 DataStore Preferences 持久化。
 */
interface SettingsRepository {
    fun observeSettings(): Flow<AppSettings>
    suspend fun updateSettings(transform: (AppSettings) -> AppSettings)
}
