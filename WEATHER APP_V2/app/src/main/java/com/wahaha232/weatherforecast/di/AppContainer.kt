// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/di/AppContainer.kt
package com.wahaha232.weatherforecast.di

import android.content.Context
import androidx.room.Room
import com.google.android.gms.location.LocationServices
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.wahaha232.weatherforecast.data.local.AppDatabase
import com.wahaha232.weatherforecast.data.local.SettingsRepositoryImpl
import com.wahaha232.weatherforecast.data.local.settingsDataStore
import com.wahaha232.weatherforecast.data.remote.OpenMeteoAirQualityApiService
import com.wahaha232.weatherforecast.data.remote.OpenMeteoForecastApiService
import com.wahaha232.weatherforecast.data.remote.OpenMeteoGeocodingApiService
import com.wahaha232.weatherforecast.data.repository.CityRepositoryImpl
import com.wahaha232.weatherforecast.data.repository.LocationRepositoryImpl
import com.wahaha232.weatherforecast.data.repository.WeatherRepositoryImpl
import com.wahaha232.weatherforecast.domain.repository.CityRepository
import com.wahaha232.weatherforecast.domain.repository.LocationRepository
import com.wahaha232.weatherforecast.domain.repository.SettingsRepository
import com.wahaha232.weatherforecast.domain.repository.WeatherRepository
import com.wahaha232.weatherforecast.domain.usecase.GetAppSettingsUseCase
import com.wahaha232.weatherforecast.domain.usecase.GetCurrentLocationUseCase
import com.wahaha232.weatherforecast.domain.usecase.GetFavoriteCitiesUseCase
import com.wahaha232.weatherforecast.domain.usecase.GetWeatherForecastUseCase
import com.wahaha232.weatherforecast.domain.usecase.SearchCitiesUseCase
import com.wahaha232.weatherforecast.domain.usecase.ToggleFavoriteCityUseCase
import com.wahaha232.weatherforecast.domain.usecase.UpdateAppSettingsUseCase
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

/**
 * 手動依賴注入容器（不引入 Hilt/Koin，改用簡單的 Service Locator 模式），
 * 在 Application 層級建立一次，透過 CompositionLocal 提供給整個 Compose 樹使用。
 * 所有物件皆為單例，生命週期與 Application 相同，不會有 Activity/ViewModel 記憶體洩漏疑慮。
 */
class AppContainer(private val appContext: Context) {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .build()

    private val forecastRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl(OpenMeteoForecastApiService.BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    private val geocodingRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl(OpenMeteoGeocodingApiService.BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    private val airQualityRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl(OpenMeteoAirQualityApiService.BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    private val forecastApiService: OpenMeteoForecastApiService =
        forecastRetrofit.create(OpenMeteoForecastApiService::class.java)

    private val geocodingApiService: OpenMeteoGeocodingApiService =
        geocodingRetrofit.create(OpenMeteoGeocodingApiService::class.java)

    private val airQualityApiService: OpenMeteoAirQualityApiService =
        airQualityRetrofit.create(OpenMeteoAirQualityApiService::class.java)

    private val database: AppDatabase = Room.databaseBuilder(
        appContext,
        AppDatabase::class.java,
        AppDatabase.DATABASE_NAME
    ).build()

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(appContext)

    // ---------- Repositories ----------
    private val weatherRepository: WeatherRepository =
        WeatherRepositoryImpl(forecastApiService, airQualityApiService)
    private val cityRepository: CityRepository =
        CityRepositoryImpl(geocodingApiService, database.favoriteCityDao())
    private val locationRepository: LocationRepository =
        LocationRepositoryImpl(appContext, fusedLocationClient)
    private val settingsRepository: SettingsRepository =
        SettingsRepositoryImpl(appContext.settingsDataStore)

    // ---------- Use Cases（依賴由此容器注入，供 Presentation 層的 ViewModel 使用） ----------
    val getWeatherForecastUseCase = GetWeatherForecastUseCase(weatherRepository)
    val searchCitiesUseCase = SearchCitiesUseCase(cityRepository)
    val getFavoriteCitiesUseCase = GetFavoriteCitiesUseCase(cityRepository)
    val toggleFavoriteCityUseCase = ToggleFavoriteCityUseCase(cityRepository)
    val getCurrentLocationUseCase = GetCurrentLocationUseCase(locationRepository)
    val getAppSettingsUseCase = GetAppSettingsUseCase(settingsRepository)
    val updateAppSettingsUseCase = UpdateAppSettingsUseCase(settingsRepository)
}
