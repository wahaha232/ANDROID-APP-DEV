// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/domain/usecase/GetAppSettingsUseCase.kt
package com.wahaha232.weatherforecast.domain.usecase

import com.wahaha232.weatherforecast.domain.model.AppSettings
import com.wahaha232.weatherforecast.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow

class GetAppSettingsUseCase(
    private val settingsRepository: SettingsRepository
) {
    operator fun invoke(): Flow<AppSettings> = settingsRepository.observeSettings()
}
