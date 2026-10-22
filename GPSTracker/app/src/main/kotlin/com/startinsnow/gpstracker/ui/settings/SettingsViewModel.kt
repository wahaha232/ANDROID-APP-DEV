package com.startinsnow.gpstracker.ui.settings

import kotlinx.coroutines.flow.first
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.startinsnow.gpstracker.GpsTrackerApplication
import com.startinsnow.gpstracker.data.prefs.AppSettings
import com.startinsnow.gpstracker.data.prefs.GpsMode
import com.startinsnow.gpstracker.data.prefs.StepSource
import com.startinsnow.gpstracker.export.BaseMapType
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
    fun setAutoPauseSeconds(seconds: Int) = viewModelScope.launch { app.settingsRepository.setAutoPauseSeconds(seconds) }

    /** 以「目前位置」新增隱私區域（例如在家的時候按）。取不到位置時 onDone(false)。 */
    fun addPrivacyZoneHere(radiusMeters: Int, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val sample = runCatching { com.startinsnow.gpstracker.location.LocationTracker(app).getLastKnownBestLocation() }.getOrNull()
        if (sample == null) { onDone(false); return@launch }
        val zones = app.settingsRepository.settings.first().privacyZones
        app.settingsRepository.setPrivacyZones(
            com.startinsnow.gpstracker.core.privacy.PrivacyZones.add(
                zones, com.startinsnow.gpstracker.core.privacy.PrivacyZone(sample.latitude, sample.longitude, radiusMeters)
            )
        )
        onDone(true)
    }

    fun removePrivacyZone(index: Int) = viewModelScope.launch {
        val zones = app.settingsRepository.settings.first().privacyZones
        app.settingsRepository.setPrivacyZones(com.startinsnow.gpstracker.core.privacy.PrivacyZones.remove(zones, index))
    }
    fun setStepSource(source: StepSource) = viewModelScope.launch { app.settingsRepository.setStepSource(source) }
    fun setDriveSync(enabled: Boolean) = viewModelScope.launch { app.settingsRepository.setDriveSyncEnabled(enabled) }
    fun setWifiOnly(enabled: Boolean) = viewModelScope.launch { app.settingsRepository.setWifiOnlySync(enabled) }
    fun setRetentionDays(days: Int) = viewModelScope.launch { app.settingsRepository.setDataRetentionDays(days) }
    fun setDeleteAfterUpload(enabled: Boolean) = viewModelScope.launch { app.settingsRepository.setDeleteLocalAfterUpload(enabled) }
    fun setBaseMapType(type: BaseMapType) = viewModelScope.launch { app.settingsRepository.setBaseMapType(type) }
    fun setOfflineMap(enabled: Boolean) = viewModelScope.launch { app.settingsRepository.setOfflineMapEnabled(enabled) }

    fun setWebUpload(enabled: Boolean) = viewModelScope.launch { app.settingsRepository.setWebUploadEnabled(enabled) }
    fun setWebRepo(value: String) = viewModelScope.launch { app.settingsRepository.setWebRepo(value) }
    fun setWebBranch(value: String) = viewModelScope.launch { app.settingsRepository.setWebBranch(value) }
    fun setWebDataPath(value: String) = viewModelScope.launch { app.settingsRepository.setWebDataPath(value) }
    fun setGithubToken(value: String) = viewModelScope.launch { app.settingsRepository.setGithubToken(value) }
    fun setWebWifiOnly(enabled: Boolean) = viewModelScope.launch { app.settingsRepository.setWebWifiOnly(enabled) }
    fun setWebUploadPhotos(enabled: Boolean) = viewModelScope.launch { app.settingsRepository.setWebUploadPhotos(enabled) }
    fun setExportApplyPrivacy(enabled: Boolean) = viewModelScope.launch { app.settingsRepository.setExportApplyPrivacy(enabled) }
    fun setDeviceName(value: String) = viewModelScope.launch { app.settingsRepository.setDeviceName(value) }
}
