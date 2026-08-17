// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/domain/usecase/UpdateAppSettingsUseCase.kt
package com.wahaha232.weatherforecast.domain.usecase

import com.wahaha232.weatherforecast.domain.model.AppSettings
import com.wahaha232.weatherforecast.domain.repository.SettingsRepository

class UpdateAppSettingsUseCase(
    private val settingsRepository: SettingsRepository
) {
    suspend operator fun invoke(transform: (AppSettings) -> AppSettings) {
        settingsRepository.updateSettings(transform)
    }
}
