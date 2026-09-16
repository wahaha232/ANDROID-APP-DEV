package com.startinsnow.gpstracker.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.startinsnow.gpstracker.GpsTrackerApplication
import com.startinsnow.gpstracker.data.prefs.AppSettings
import com.startinsnow.gpstracker.data.prefs.GpsMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app get() = getApplication<GpsTrackerApplication>()

    val settings: StateFlow<AppSettings> = app.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    fun setGpsMode(mode: GpsMode) = viewModelScope.launch { app.settingsRepository.setGpsMode(mode) }
    fun setAutoPause(enabled: Boolean) = viewModelScope.launch { app.settingsRepository.setAutoPauseEnabled(enabled) }
    fun setDriveSync(enabled: Boolean) = viewModelScope.launch { app.settingsRepository.setDriveSyncEnabled(enabled) }
    fun setWifiOnly(enabled: Boolean) = viewModelScope.launch { app.settingsRepository.setWifiOnlySync(enabled) }
    fun setRetentionDays(days: Int) = viewModelScope.launch { app.settingsRepository.setDataRetentionDays(days) }
    fun setDeleteAfterUpload(enabled: Boolean) = viewModelScope.launch { app.settingsRepository.setDeleteLocalAfterUpload(enabled) }
    fun setOfflineMap(enabled: Boolean) = viewModelScope.launch { app.settingsRepository.setOfflineMapEnabled(enabled) }
}
