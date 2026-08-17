// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/presentation/weather/WeatherViewModel.kt
package com.wahaha232.weatherforecast.presentation.weather

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wahaha232.weatherforecast.domain.model.City
import com.wahaha232.weatherforecast.domain.usecase.GetCurrentLocationUseCase
import com.wahaha232.weatherforecast.domain.usecase.GetFavoriteCitiesUseCase
import com.wahaha232.weatherforecast.domain.usecase.GetWeatherForecastUseCase
import com.wahaha232.weatherforecast.domain.usecase.ReverseGeocodeCityUseCase
import com.wahaha232.weatherforecast.domain.usecase.ToggleFavoriteCityUseCase
import com.wahaha232.weatherforecast.domain.util.Resource
import com.wahaha232.weatherforecast.widget.WeatherWidgetUpdater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * MVI ViewModel：對外只暴露單一 StateFlow<WeatherUiState> 與 onIntent(...) 入口，
 * 所有耗時工作皆以 viewModelScope 啟動，隨 ViewModel 生命週期自動取消，不會有 Job 洩漏。
 */
class WeatherViewModel(
    private val appContext: Context,
    private val getWeatherForecastUseCase: GetWeatherForecastUseCase,
    private val getCurrentLocationUseCase: GetCurrentLocationUseCase,
    private val reverseGeocodeCityUseCase: ReverseGeocodeCityUseCase,
    private val toggleFavoriteCityUseCase: ToggleFavoriteCityUseCase,
    getFavoriteCitiesUseCase: GetFavoriteCitiesUseCase,
    initialCity: City
) : ViewModel() {

    private val _uiState = MutableStateFlow<WeatherUiState>(WeatherUiState.Loading)
    val uiState: StateFlow<WeatherUiState> = _uiState.asStateFlow()

    /** 供左側抽屜選單顯示最愛城市清單使用 */
    val favoriteCities: StateFlow<List<City>> = getFavoriteCitiesUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var activeCity: City = initialCity
    private var favoriteCityIds: Set<Long> = emptySet()

    init {
        viewModelScope.launch {
            favoriteCities.collect { favorites ->
                favoriteCityIds = favorites.map { it.id }.toSet()
                syncFavoriteFlagIntoCurrentState()
            }
        }
        onIntent(WeatherIntent.LoadWeather(initialCity))
    }

    fun onIntent(intent: WeatherIntent) {
        when (intent) {
            is WeatherIntent.LoadWeather -> {
                activeCity = intent.city
                loadForecast(showLoading = true)
            }
            WeatherIntent.Refresh -> loadForecast(showLoading = false, isRefresh = true)
            WeatherIntent.Retry -> loadForecast(showLoading = true)
            WeatherIntent.UseCurrentLocation -> useCurrentLocation()
            WeatherIntent.ToggleFavorite -> toggleFavorite()
        }
    }

    private fun loadForecast(showLoading: Boolean, isRefresh: Boolean = false) {
        if (showLoading) {
            _uiState.value = WeatherUiState.Loading
        } else if (isRefresh) {
            val current = _uiState.value
            if (current is WeatherUiState.Success) {
                _uiState.value = current.copy(isRefreshing = true)
            }
        }

        viewModelScope.launch {
            when (val result = getWeatherForecastUseCase(activeCity)) {
                is Resource.Success -> {
                    _uiState.value = WeatherUiState.Success(
                        forecast = result.data,
                        isRefreshing = false,
                        isFavorite = favoriteCityIds.contains(activeCity.id)
                    )
                    // 讓桌面小工具與 App 內顯示的天氣保持同步
                    WeatherWidgetUpdater.pushForecast(appContext, result.data)
                }
                is Resource.Error -> {
                    val previous = _uiState.value
                    _uiState.value = if (previous is WeatherUiState.Success) {
                        // 已有資料時，重新整理失敗不清空畫面，只結束 loading 指示
                        previous.copy(isRefreshing = false)
                    } else {
                        WeatherUiState.Error(result.message)
                    }
                }
                Resource.Loading -> Unit
            }
        }
    }

    private fun useCurrentLocation() {
        viewModelScope.launch {
            _uiState.value = WeatherUiState.Loading
            when (val locationResult = getCurrentLocationUseCase()) {
                is Resource.Success -> {
                    val lat = locationResult.data.latitude
                    val lon = locationResult.data.longitude

                    // 反向地理編碼解析精確到村里的地址；失敗時退回座標命名的通用「目前位置」，
                    // 不阻擋使用者取得天氣資料（定位資訊比不上精確地名重要）。
                    val fallbackCity = City(
                        id = -(lat * 1000).toLong(),
                        name = "目前位置",
                        admin1 = null,
                        country = null,
                        countryCode = null,
                        latitude = lat,
                        longitude = lon,
                        timezone = null
                    )
                    val resolvedCity = when (val geocodeResult = reverseGeocodeCityUseCase(lat, lon)) {
                        is Resource.Success -> geocodeResult.data
                        else -> fallbackCity
                    }

                    activeCity = resolvedCity
                    loadForecast(showLoading = true)
                }
                is Resource.Error -> {
                    _uiState.value = WeatherUiState.Error(locationResult.message)
                }
                Resource.Loading -> Unit
            }
        }
    }

    private fun toggleFavorite() {
        viewModelScope.launch {
            toggleFavoriteCityUseCase(activeCity)
        }
    }

    private fun syncFavoriteFlagIntoCurrentState() {
        val current = _uiState.value
        if (current is WeatherUiState.Success) {
            _uiState.value = current.copy(isFavorite = favoriteCityIds.contains(activeCity.id))
        }
    }
}
