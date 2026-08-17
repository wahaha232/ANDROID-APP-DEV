// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/presentation/weather/WeatherScreen.kt
package com.wahaha232.weatherforecast.presentation.weather

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wahaha232.weatherforecast.data.worker.WeatherNotificationScheduler
import com.wahaha232.weatherforecast.domain.model.City
import com.wahaha232.weatherforecast.presentation.settings.AppInfoActions
import com.wahaha232.weatherforecast.presentation.settings.SettingsViewModel
import com.wahaha232.weatherforecast.presentation.settings.WeatherDrawerContent
import com.wahaha232.weatherforecast.presentation.weather.components.AdBannerView
import com.wahaha232.weatherforecast.presentation.weather.components.AllergensCard
import com.wahaha232.weatherforecast.presentation.weather.components.AqiCard
import com.wahaha232.weatherforecast.presentation.weather.components.CurrentWeatherCard
import com.wahaha232.weatherforecast.presentation.weather.components.DailyForecastRow
import com.wahaha232.weatherforecast.presentation.weather.components.DailyForecastSectionHeader
import com.wahaha232.weatherforecast.presentation.weather.components.ErrorView
import com.wahaha232.weatherforecast.presentation.weather.components.HourlyForecastRow
import com.wahaha232.weatherforecast.presentation.weather.components.PhotographyCard
import com.wahaha232.weatherforecast.presentation.weather.components.RadarMapCard
import com.wahaha232.weatherforecast.presentation.weather.components.SunMoonCard
import com.wahaha232.weatherforecast.presentation.weather.components.WeatherLoadingSkeleton
import com.wahaha232.weatherforecast.presentation.weather.components.WindPressureCard
import com.wahaha232.weatherforecast.presentation.weather.permission.LocationPermissionRationaleCard
import com.wahaha232.weatherforecast.presentation.weather.permission.rememberLocationPermissionState
import kotlinx.coroutines.launch

/**
 * 天氣主畫面：左側抽屜選單 + 下拉刷新 + 依 UiState 切換 Loading/Success/Error 三種畫面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeatherScreen(
    weatherViewModel: WeatherViewModel,
    settingsViewModel: SettingsViewModel,
    onOpenSearch: () -> Unit
) {
    val uiState by weatherViewModel.uiState.collectAsStateWithLifecycle()
    val favoriteCities by weatherViewModel.favoriteCities.collectAsStateWithLifecycle()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var showLocationRationale by remember { mutableStateOf(true) }

    val permissionController = rememberLocationPermissionState { granted ->
        if (granted) weatherViewModel.onIntent(WeatherIntent.UseCurrentLocation)
    }

    LaunchedEffect(settings.dailyWeatherNotificationEnabled) {
        if (settings.dailyWeatherNotificationEnabled) {
            WeatherNotificationScheduler.schedule(context)
        } else {
            WeatherNotificationScheduler.cancel(context)
        }
    }

    val currentCity = (uiState as? WeatherUiState.Success)?.forecast?.city

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            WeatherDrawerContent(
                currentCity = currentCity ?: DEFAULT_DRAWER_CITY,
                favoriteCities = favoriteCities,
                settings = settings,
                onSelectCity = { city ->
                    weatherViewModel.onIntent(WeatherIntent.LoadWeather(city))
                    coroutineScope.launch { drawerState.close() }
                },
                onUseCurrentLocation = {
                    if (permissionController.isGranted) {
                        weatherViewModel.onIntent(WeatherIntent.UseCurrentLocation)
                    } else {
                        permissionController.requestPermission()
                    }
                    coroutineScope.launch { drawerState.close() }
                },
                onEditLocation = {
                    coroutineScope.launch { drawerState.close() }
                    onOpenSearch()
                },
                onUpdateSettings = { newSettings -> settingsViewModel.applyAll(newSettings) },
                onRateApp = { AppInfoActions.openPlayStoreListing(context) },
                onSendFeedback = { AppInfoActions.sendFeedbackEmail(context) },
                onShareApp = { AppInfoActions.shareApp(context) },
                onOpenPrivacyPolicy = { AppInfoActions.openPrivacyPolicy(context) },
                versionName = AppInfoActions.appVersionName(context)
            )
        }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(currentCity?.name ?: "天氣預報") },
                    navigationIcon = {
                        IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "選單")
                        }
                    },
                    actions = {
                        if (uiState is WeatherUiState.Success) {
                            val isFavorite = (uiState as WeatherUiState.Success).isFavorite
                            IconButton(onClick = { weatherViewModel.onIntent(WeatherIntent.ToggleFavorite) }) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                                    contentDescription = "收藏"
                                )
                            }
                        }
                        IconButton(onClick = { weatherViewModel.onIntent(WeatherIntent.Refresh) }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "重新整理")
                        }
                    }
                )
            }
        ) { padding ->
            when (val state = uiState) {
                WeatherUiState.Loading -> WeatherLoadingSkeleton(modifier = Modifier.padding(padding))
                is WeatherUiState.Error -> ErrorView(
                    message = state.message,
                    onRetry = { weatherViewModel.onIntent(WeatherIntent.Retry) },
                    modifier = Modifier.padding(padding)
                )
                is WeatherUiState.Success -> {
                    val forecast = state.forecast
                    val today = forecast.daily.firstOrNull()

                    PullToRefreshBox(
                        isRefreshing = state.isRefreshing,
                        onRefresh = { weatherViewModel.onIntent(WeatherIntent.Refresh) },
                        modifier = Modifier.padding(padding).fillMaxSize()
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            item { CurrentWeatherCard(forecast.city, forecast.current, today, settings) }
                            item { AdBannerView() }
                            item { HourlyForecastRow(forecast.hourly, settings) }
                            item { DailyForecastSectionHeader() }
                            items(forecast.daily) { day ->
                                DailyForecastRow(day, settings, isToday = day == forecast.daily.first())
                            }
                            item { WindPressureCard(forecast.current, settings) }
                            item { AqiCard(forecast.airQuality) }
                            item { AllergensCard(forecast.current, forecast.airQuality) }
                            item { PhotographyCard(today) }
                            item { RadarMapCard(forecast.city) }
                            item { SunMoonCard(today, forecast.lastUpdatedEpochMillis) }
                        }
                    }
                }
            }

            if (showLocationRationale && uiState is WeatherUiState.Loading && !permissionController.isGranted) {
                LocationPermissionRationaleCard(
                    onAllow = {
                        showLocationRationale = false
                        permissionController.requestPermission()
                    },
                    onDismiss = { showLocationRationale = false },
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }
}

private val DEFAULT_DRAWER_CITY = City(
    id = 1668341,
    name = "台北市",
    admin1 = "台灣",
    country = "台灣",
    countryCode = "TW",
    latitude = 25.033,
    longitude = 121.5654,
    timezone = "Asia/Taipei"
)
