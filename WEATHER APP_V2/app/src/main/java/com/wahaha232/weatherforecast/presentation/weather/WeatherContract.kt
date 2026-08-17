// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/presentation/weather/WeatherContract.kt
package com.wahaha232.weatherforecast.presentation.weather

import com.wahaha232.weatherforecast.domain.model.City
import com.wahaha232.weatherforecast.domain.model.WeatherForecast

/**
 * MVI 的 UI State：畫面在任一時刻只會處於下列其中一種狀態。
 */
sealed interface WeatherUiState {
    data object Loading : WeatherUiState
    data class Success(
        val forecast: WeatherForecast,
        val isRefreshing: Boolean = false,
        val isFavorite: Boolean = false
    ) : WeatherUiState
    data class Error(val message: String) : WeatherUiState
}

/**
 * MVI 的 Intent：所有使用者互動或系統事件都以 Intent 表達，
 * ViewModel 只暴露 onIntent(...) 單一入口處理，UI 不直接呼叫多個方法。
 */
sealed interface WeatherIntent {
    data class LoadWeather(val city: City) : WeatherIntent
    data object Refresh : WeatherIntent
    data object Retry : WeatherIntent
    data object UseCurrentLocation : WeatherIntent
    data object ToggleFavorite : WeatherIntent
}
