// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/data/local/SettingsRepositoryImpl.kt
package com.wahaha232.weatherforecast.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.wahaha232.weatherforecast.domain.model.AppSettings
import com.wahaha232.weatherforecast.domain.model.PrecipitationUnit
import com.wahaha232.weatherforecast.domain.model.PressureUnit
import com.wahaha232.weatherforecast.domain.model.TemperatureUnit
import com.wahaha232.weatherforecast.domain.model.VisibilityUnit
import com.wahaha232.weatherforecast.domain.model.WindSpeedUnit
import com.wahaha232.weatherforecast.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 以 DataStore Preferences 持久化 App 設定（單位偏好、功能開關）。
 * 與 Room 分工：Room 負責「結構化清單」（最愛城市），DataStore 負責「單一鍵值設定」。
 */
class SettingsRepositoryImpl(
    private val dataStore: DataStore<Preferences>
) : SettingsRepository {

    private object Keys {
        val TEMPERATURE_UNIT = stringPreferencesKey("temperature_unit")
        val WIND_SPEED_UNIT = stringPreferencesKey("wind_speed_unit")
        val PRESSURE_UNIT = stringPreferencesKey("pressure_unit")
        val VISIBILITY_UNIT = stringPreferencesKey("visibility_unit")
        val PRECIPITATION_UNIT = stringPreferencesKey("precipitation_unit")
        val DAILY_NOTIFICATION = booleanPreferencesKey("daily_weather_notification_enabled")
        val WEATHER_BACKGROUND = booleanPreferencesKey("weather_background_enabled")
        val SHOW_NIGHT_INFO = booleanPreferencesKey("show_night_info")
    }

    override fun observeSettings(): Flow<AppSettings> {
        return dataStore.data.map { prefs ->
            AppSettings(
                temperatureUnit = prefs[Keys.TEMPERATURE_UNIT]?.let { runCatching { TemperatureUnit.valueOf(it) }.getOrNull() }
                    ?: TemperatureUnit.CELSIUS,
                windSpeedUnit = prefs[Keys.WIND_SPEED_UNIT]?.let { runCatching { WindSpeedUnit.valueOf(it) }.getOrNull() }
                    ?: WindSpeedUnit.KMH,
                pressureUnit = prefs[Keys.PRESSURE_UNIT]?.let { runCatching { PressureUnit.valueOf(it) }.getOrNull() }
                    ?: PressureUnit.MBAR,
                visibilityUnit = prefs[Keys.VISIBILITY_UNIT]?.let { runCatching { VisibilityUnit.valueOf(it) }.getOrNull() }
                    ?: VisibilityUnit.KM,
                precipitationUnit = prefs[Keys.PRECIPITATION_UNIT]?.let { runCatching { PrecipitationUnit.valueOf(it) }.getOrNull() }
                    ?: PrecipitationUnit.MM,
                dailyWeatherNotificationEnabled = prefs[Keys.DAILY_NOTIFICATION] ?: false,
                weatherBackgroundEnabled = prefs[Keys.WEATHER_BACKGROUND] ?: true,
                showNightInfo = prefs[Keys.SHOW_NIGHT_INFO] ?: true
            )
        }
    }

    override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val current = AppSettings(
                temperatureUnit = prefs[Keys.TEMPERATURE_UNIT]?.let { runCatching { TemperatureUnit.valueOf(it) }.getOrNull() }
                    ?: TemperatureUnit.CELSIUS,
                windSpeedUnit = prefs[Keys.WIND_SPEED_UNIT]?.let { runCatching { WindSpeedUnit.valueOf(it) }.getOrNull() }
                    ?: WindSpeedUnit.KMH,
                pressureUnit = prefs[Keys.PRESSURE_UNIT]?.let { runCatching { PressureUnit.valueOf(it) }.getOrNull() }
                    ?: PressureUnit.MBAR,
                visibilityUnit = prefs[Keys.VISIBILITY_UNIT]?.let { runCatching { VisibilityUnit.valueOf(it) }.getOrNull() }
                    ?: VisibilityUnit.KM,
                precipitationUnit = prefs[Keys.PRECIPITATION_UNIT]?.let { runCatching { PrecipitationUnit.valueOf(it) }.getOrNull() }
                    ?: PrecipitationUnit.MM,
                dailyWeatherNotificationEnabled = prefs[Keys.DAILY_NOTIFICATION] ?: false,
                weatherBackgroundEnabled = prefs[Keys.WEATHER_BACKGROUND] ?: true,
                showNightInfo = prefs[Keys.SHOW_NIGHT_INFO] ?: true
            )
            val updated = transform(current)
            prefs[Keys.TEMPERATURE_UNIT] = updated.temperatureUnit.name
            prefs[Keys.WIND_SPEED_UNIT] = updated.windSpeedUnit.name
            prefs[Keys.PRESSURE_UNIT] = updated.pressureUnit.name
            prefs[Keys.VISIBILITY_UNIT] = updated.visibilityUnit.name
            prefs[Keys.PRECIPITATION_UNIT] = updated.precipitationUnit.name
            prefs[Keys.DAILY_NOTIFICATION] = updated.dailyWeatherNotificationEnabled
            prefs[Keys.WEATHER_BACKGROUND] = updated.weatherBackgroundEnabled
            prefs[Keys.SHOW_NIGHT_INFO] = updated.showNightInfo
        }
    }
}
