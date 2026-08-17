// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/presentation/settings/SettingsViewModel.kt
package com.wahaha232.weatherforecast.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wahaha232.weatherforecast.domain.model.AppSettings
import com.wahaha232.weatherforecast.domain.model.PrecipitationUnit
import com.wahaha232.weatherforecast.domain.model.PressureUnit
import com.wahaha232.weatherforecast.domain.model.TemperatureUnit
import com.wahaha232.weatherforecast.domain.model.VisibilityUnit
import com.wahaha232.weatherforecast.domain.model.WindSpeedUnit
import com.wahaha232.weatherforecast.domain.usecase.GetAppSettingsUseCase
import com.wahaha232.weatherforecast.domain.usecase.UpdateAppSettingsUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 側邊選單「功能」區塊（單位偏好 + 開關）對應的 ViewModel。
 * 使用 stateIn 將 DataStore 的 Flow 轉為 StateFlow，並在沒有訂閱者 5 秒後自動停止收集，
 * 避免背景無用的資料流持續佔用資源。
 */
class SettingsViewModel(
    getAppSettingsUseCase: GetAppSettingsUseCase,
    private val updateAppSettingsUseCase: UpdateAppSettingsUseCase
) : ViewModel() {

    val settings: StateFlow<AppSettings> = getAppSettingsUseCase()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppSettings()
        )

    fun setTemperatureUnit(unit: TemperatureUnit) = update { it.copy(temperatureUnit = unit) }
    fun setWindSpeedUnit(unit: WindSpeedUnit) = update { it.copy(windSpeedUnit = unit) }
    fun setPressureUnit(unit: PressureUnit) = update { it.copy(pressureUnit = unit) }
    fun setVisibilityUnit(unit: VisibilityUnit) = update { it.copy(visibilityUnit = unit) }
    fun setPrecipitationUnit(unit: PrecipitationUnit) = update { it.copy(precipitationUnit = unit) }
    fun setDailyWeatherNotificationEnabled(enabled: Boolean) =
        update { it.copy(dailyWeatherNotificationEnabled = enabled) }
    fun setWeatherBackgroundEnabled(enabled: Boolean) = update { it.copy(weatherBackgroundEnabled = enabled) }
    fun setShowNightInfo(enabled: Boolean) = update { it.copy(showNightInfo = enabled) }

    /** 側邊選單直接以整包 AppSettings 回呼更新（單一 copy 過的欄位），一次寫入即可 */
    fun applyAll(newSettings: AppSettings) = update { newSettings }

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { updateAppSettingsUseCase(transform) }
    }
}
