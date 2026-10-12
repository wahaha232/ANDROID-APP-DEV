package com.startinsnow.gpstracker.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "gps_tracker_settings")

enum class GpsMode { ADAPTIVE, HIGH_ACCURACY }

/** 步數來源：裝置感測器、Google Fit，或小米 Zepp Life（經 Health Connect）。 */
enum class StepSource { DEVICE_SENSOR, GOOGLE_FIT, ZEPP_LIFE }

data class AppSettings(
    val gpsMode: GpsMode = GpsMode.ADAPTIVE,
    val autoPauseEnabled: Boolean = true,
    val stepSource: StepSource = StepSource.ZEPP_LIFE,
    val driveSyncEnabled: Boolean = false,
    val wifiOnlySync: Boolean = true,
    val dataRetentionDays: Int = 0, // 0 = 永久保留
    val deleteLocalAfterUploadEnabled: Boolean = false,
    val offlineMapEnabled: Boolean = false,
    // 網頁儀表板（GitHub Pages）：記錄結束後自動上傳 JSON。
    val webUploadEnabled: Boolean = false,
    val webRepo: String = "wahaha232/ANDROID-APP-DEV",
    val webBranch: String = "main",
    val webDataPath: String = "gpstracker-web/data",
    val githubToken: String = "",
    val webWifiOnly: Boolean = true
) {
    // 刻意覆寫：data class 預設的 toString() 會把 GitHub Token 印出來（log、除錯輸出、例外訊息都可能帶到）。
    override fun toString(): String =
        "AppSettings(gpsMode=$gpsMode, autoPauseEnabled=$autoPauseEnabled, stepSource=$stepSource, " +
            "driveSyncEnabled=$driveSyncEnabled, wifiOnlySync=$wifiOnlySync, dataRetentionDays=$dataRetentionDays, " +
            "deleteLocalAfterUploadEnabled=$deleteLocalAfterUploadEnabled, offlineMapEnabled=$offlineMapEnabled, " +
            "webUploadEnabled=$webUploadEnabled, webRepo=$webRepo, webBranch=$webBranch, webDataPath=$webDataPath, " +
            "githubToken=${if (githubToken.isEmpty()) "<empty>" else "<redacted>"}, webWifiOnly=$webWifiOnly)"
}

/** 對應規格「64. 設定」。只放置真正有實作對應功能的設定項，避免「假設定」。 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val GPS_MODE = stringPreferencesKey("gps_mode")
        val AUTO_PAUSE = booleanPreferencesKey("auto_pause_enabled")
        val STEP_SOURCE = stringPreferencesKey("step_source")
        val DRIVE_SYNC = booleanPreferencesKey("drive_sync_enabled")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only_sync")
        val RETENTION_DAYS = intPreferencesKey("data_retention_days")
        val DELETE_AFTER_UPLOAD = booleanPreferencesKey("delete_local_after_upload")
        val OFFLINE_MAP = booleanPreferencesKey("offline_map_enabled")
        val WEB_UPLOAD = booleanPreferencesKey("web_upload_enabled")
        val WEB_REPO = stringPreferencesKey("web_repo")
        val WEB_BRANCH = stringPreferencesKey("web_branch")
        val WEB_DATA_PATH = stringPreferencesKey("web_data_path")
        val GITHUB_TOKEN = stringPreferencesKey("github_token")
        val WEB_WIFI_ONLY = booleanPreferencesKey("web_wifi_only")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            gpsMode = runCatching { GpsMode.valueOf(prefs[Keys.GPS_MODE] ?: GpsMode.ADAPTIVE.name) }.getOrDefault(GpsMode.ADAPTIVE),
            autoPauseEnabled = prefs[Keys.AUTO_PAUSE] ?: true,
            stepSource = runCatching { StepSource.valueOf(prefs[Keys.STEP_SOURCE] ?: StepSource.ZEPP_LIFE.name) }.getOrDefault(StepSource.ZEPP_LIFE),
            driveSyncEnabled = prefs[Keys.DRIVE_SYNC] ?: false,
            wifiOnlySync = prefs[Keys.WIFI_ONLY] ?: true,
            dataRetentionDays = prefs[Keys.RETENTION_DAYS] ?: 0,
            deleteLocalAfterUploadEnabled = prefs[Keys.DELETE_AFTER_UPLOAD] ?: false,
            offlineMapEnabled = prefs[Keys.OFFLINE_MAP] ?: false,
            webUploadEnabled = prefs[Keys.WEB_UPLOAD] ?: false,
            webRepo = prefs[Keys.WEB_REPO] ?: "wahaha232/ANDROID-APP-DEV",
            webBranch = prefs[Keys.WEB_BRANCH] ?: "main",
            webDataPath = prefs[Keys.WEB_DATA_PATH] ?: "gpstracker-web/data",
            githubToken = SecretCipher.decrypt(prefs[Keys.GITHUB_TOKEN] ?: ""),
            webWifiOnly = prefs[Keys.WEB_WIFI_ONLY] ?: true
        )
    }.flowOn(Dispatchers.IO) // 解密要呼叫 Keystore，不放在主執行緒做

    suspend fun setGpsMode(mode: GpsMode) = context.dataStore.edit { it[Keys.GPS_MODE] = mode.name }
    suspend fun setAutoPauseEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.AUTO_PAUSE] = enabled }
    suspend fun setStepSource(source: StepSource) = context.dataStore.edit { it[Keys.STEP_SOURCE] = source.name }
    suspend fun setDriveSyncEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.DRIVE_SYNC] = enabled }
    suspend fun setWifiOnlySync(enabled: Boolean) = context.dataStore.edit { it[Keys.WIFI_ONLY] = enabled }
    suspend fun setDataRetentionDays(days: Int) = context.dataStore.edit { it[Keys.RETENTION_DAYS] = days }
    suspend fun setDeleteLocalAfterUpload(enabled: Boolean) = context.dataStore.edit { it[Keys.DELETE_AFTER_UPLOAD] = enabled }
    suspend fun setOfflineMapEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.OFFLINE_MAP] = enabled }

    suspend fun setWebUploadEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.WEB_UPLOAD] = enabled }
    suspend fun setWebRepo(value: String) = context.dataStore.edit { it[Keys.WEB_REPO] = value }
    suspend fun setWebBranch(value: String) = context.dataStore.edit { it[Keys.WEB_BRANCH] = value }
    suspend fun setWebDataPath(value: String) = context.dataStore.edit { it[Keys.WEB_DATA_PATH] = value }
    suspend fun setGithubToken(value: String) = context.dataStore.edit { it[Keys.GITHUB_TOKEN] = SecretCipher.encrypt(value.trim()) }

    /** 舊版把 Token 以明文存放；App 啟動時一次性轉成 Keystore 加密後的格式。 */
    suspend fun migrateLegacySecrets() {
        context.dataStore.edit { prefs ->
            val raw = prefs[Keys.GITHUB_TOKEN]
            if (!raw.isNullOrEmpty() && !SecretCipher.isEncrypted(raw)) {
                prefs[Keys.GITHUB_TOKEN] = SecretCipher.encrypt(raw)
            }
        }
    }
    suspend fun setWebWifiOnly(enabled: Boolean) = context.dataStore.edit { it[Keys.WEB_WIFI_ONLY] = enabled }
}
