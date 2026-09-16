package com.startinsnow.gpstracker.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "gps_tracker_settings")

enum class GpsMode { ADAPTIVE, HIGH_ACCURACY }

data class AppSettings(
    val gpsMode: GpsMode = GpsMode.ADAPTIVE,
    val autoPauseEnabled: Boolean = true,
    val driveSyncEnabled: Boolean = false,
    val wifiOnlySync: Boolean = true,
    val dataRetentionDays: Int = 0, // 0 = 永久保留
    val deleteLocalAfterUploadEnabled: Boolean = false,
    val offlineMapEnabled: Boolean = false
)

/** 對應規格「64. 設定」。只放置真正有實作對應功能的設定項，避免「假設定」。 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val GPS_MODE = stringPreferencesKey("gps_mode")
        val AUTO_PAUSE = booleanPreferencesKey("auto_pause_enabled")
        val DRIVE_SYNC = booleanPreferencesKey("drive_sync_enabled")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only_sync")
        val RETENTION_DAYS = intPreferencesKey("data_retention_days")
        val DELETE_AFTER_UPLOAD = booleanPreferencesKey("delete_local_after_upload")
        val OFFLINE_MAP = booleanPreferencesKey("offline_map_enabled")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            gpsMode = runCatching { GpsMode.valueOf(prefs[Keys.GPS_MODE] ?: GpsMode.ADAPTIVE.name) }.getOrDefault(GpsMode.ADAPTIVE),
            autoPauseEnabled = prefs[Keys.AUTO_PAUSE] ?: true,
            driveSyncEnabled = prefs[Keys.DRIVE_SYNC] ?: false,
            wifiOnlySync = prefs[Keys.WIFI_ONLY] ?: true,
            dataRetentionDays = prefs[Keys.RETENTION_DAYS] ?: 0,
            deleteLocalAfterUploadEnabled = prefs[Keys.DELETE_AFTER_UPLOAD] ?: false,
            offlineMapEnabled = prefs[Keys.OFFLINE_MAP] ?: false
        )
    }

    suspend fun setGpsMode(mode: GpsMode) = context.dataStore.edit { it[Keys.GPS_MODE] = mode.name }
    suspend fun setAutoPauseEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.AUTO_PAUSE] = enabled }
    suspend fun setDriveSyncEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.DRIVE_SYNC] = enabled }
    suspend fun setWifiOnlySync(enabled: Boolean) = context.dataStore.edit { it[Keys.WIFI_ONLY] = enabled }
    suspend fun setDataRetentionDays(days: Int) = context.dataStore.edit { it[Keys.RETENTION_DAYS] = days }
    suspend fun setDeleteLocalAfterUpload(enabled: Boolean) = context.dataStore.edit { it[Keys.DELETE_AFTER_UPLOAD] = enabled }
    suspend fun setOfflineMapEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.OFFLINE_MAP] = enabled }
}
