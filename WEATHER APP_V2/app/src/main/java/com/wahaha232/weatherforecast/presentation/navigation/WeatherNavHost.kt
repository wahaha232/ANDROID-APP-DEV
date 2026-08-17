// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/presentation/navigation/WeatherNavHost.kt
package com.wahaha232.weatherforecast.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.wahaha232.weatherforecast.di.AppContainer
import com.wahaha232.weatherforecast.domain.model.City
import com.wahaha232.weatherforecast.presentation.search.SearchScreen
import com.wahaha232.weatherforecast.presentation.search.SearchViewModel
import com.wahaha232.weatherforecast.presentation.settings.SettingsViewModel
import com.wahaha232.weatherforecast.presentation.weather.WeatherIntent
import com.wahaha232.weatherforecast.presentation.weather.WeatherScreen
import com.wahaha232.weatherforecast.presentation.weather.WeatherViewModel

private const val ROUTE_WEATHER = "weather"
private const val ROUTE_SEARCH = "search"

private val DEFAULT_START_CITY = City(
    id = 1668341,
    name = "台北市",
    admin1 = "台灣",
    country = "台灣",
    countryCode = "TW",
    latitude = 25.033,
    longitude = 121.5654,
    timezone = "Asia/Taipei"
)

/**
 * 整個 App 的導航圖：天氣主畫面 <-> 城市搜尋畫面。
 * WeatherViewModel 在 NavHost 這一層建立（scope 綁在整個導航圖生命週期），
 * 讓 SearchScreen 選城市後可以直接呼叫同一個 WeatherViewModel 切換城市。
 */
@Composable
fun WeatherNavHost(
    container: AppContainer,
    navController: NavHostController = rememberNavController()
) {
    val appContext = LocalContext.current.applicationContext

    val weatherViewModel: WeatherViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                WeatherViewModel(
                    appContext = appContext,
                    getWeatherForecastUseCase = container.getWeatherForecastUseCase,
                    getCurrentLocationUseCase = container.getCurrentLocationUseCase,
                    reverseGeocodeCityUseCase = container.reverseGeocodeCityUseCase,
                    toggleFavoriteCityUseCase = container.toggleFavoriteCityUseCase,
                    getFavoriteCitiesUseCase = container.getFavoriteCitiesUseCase,
                    initialCity = DEFAULT_START_CITY
                )
            }
        }
    )

    val settingsViewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    getAppSettingsUseCase = container.getAppSettingsUseCase,
                    updateAppSettingsUseCase = container.updateAppSettingsUseCase
                )
            }
        }
    )

    NavHost(navController = navController, startDestination = ROUTE_WEATHER) {
        composable(ROUTE_WEATHER) {
            WeatherScreen(
                weatherViewModel = weatherViewModel,
                settingsViewModel = settingsViewModel,
                onOpenSearch = { navController.navigate(ROUTE_SEARCH) }
            )
        }
        composable(ROUTE_SEARCH) {
            val searchViewModel: SearchViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        SearchViewModel(
                            searchCitiesUseCase = container.searchCitiesUseCase,
                            toggleFavoriteCityUseCase = container.toggleFavoriteCityUseCase,
                            getFavoriteCitiesUseCase = container.getFavoriteCitiesUseCase
                        )
                    }
                }
            )
            SearchScreen(
                viewModel = searchViewModel,
                onBack = { navController.popBackStack() },
                onCitySelected = { city ->
                    weatherViewModel.onIntent(WeatherIntent.LoadWeather(city))
                    navController.popBackStack()
                }
            )
        }
    }
}
