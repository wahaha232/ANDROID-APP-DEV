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
    /** 位置靜止多久後自動暫停（秒；介面以 時／分／秒 呈現），見 [AutoPauseDelay]。 */
    val autoPauseSeconds: Int = AutoPauseDelay.DEFAULT_SECONDS,
    /** 隱私區域：上傳到網頁與分享圖片時，會移除軌跡開頭／結尾落在區域內的點（見 PrivacyZones）。 */
    val privacyZones: List<com.startinsnow.gpstracker.core.privacy.PrivacyZone> = emptyList(),
    val stepSource: StepSource = StepSource.GOOGLE_FIT,
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
    val webWifiOnly: Boolean = true,
    /** 上傳照片縮圖到（公開的）網頁儀表板。預設關閉：照片會公開在 GitHub 倉庫。 */
    val webUploadPhotos: Boolean = false,
    /** 匯出分享 ZIP 時套用隱私區域（裁掉頭尾、不含區域內照片）。預設關閉；Drive 備份與本機資料不受影響。 */
    val exportApplyPrivacy: Boolean = false,
    /** 裝置名稱：上傳紀錄時標記來源手機，網頁可依裝置切換檢視。空白時以手機型號代替。 */
    val deviceName: String = ""
) {
    // 刻意覆寫：data class 預設的 toString() 會把 GitHub Token 印出來（log、除錯輸出、例外訊息都可能帶到）。
    override fun toString(): String =
        "AppSettings(gpsMode=$gpsMode, autoPauseEnabled=$autoPauseEnabled, autoPauseSeconds=$autoPauseSeconds, stepSource=$stepSource, " +
            "driveSyncEnabled=$driveSyncEnabled, wifiOnlySync=$wifiOnlySync, dataRetentionDays=$dataRetentionDays, " +
            "deleteLocalAfterUploadEnabled=$deleteLocalAfterUploadEnabled, offlineMapEnabled=$offlineMapEnabled, " +
            "webUploadEnabled=$webUploadEnabled, webRepo=$webRepo, webBranch=$webBranch, webDataPath=$webDataPath, " +
            "githubToken=${if (githubToken.isEmpty()) "<empty>" else "<redacted>"}, webWifiOnly=$webWifiOnly, deviceName=$deviceName)"
}

/** 對應規格「64. 設定」。只放置真正有實作對應功能的設定項，避免「假設定」。 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val GPS_MODE = stringPreferencesKey("gps_mode")
        val AUTO_PAUSE = booleanPreferencesKey("auto_pause_enabled")
        val AUTO_PAUSE_SECONDS = intPreferencesKey("auto_pause_seconds")
        val PRIVACY_ZONES = stringPreferencesKey("privacy_zones")
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
        val WEB_UPLOAD_PHOTOS = booleanPreferencesKey("web_upload_photos")
        val EXPORT_PRIVACY = booleanPreferencesKey("export_apply_privacy")
        val DEVICE_NAME = stringPreferencesKey("device_name")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            gpsMode = runCatching { GpsMode.valueOf(prefs[Keys.GPS_MODE] ?: GpsMode.ADAPTIVE.name) }.getOrDefault(GpsMode.ADAPTIVE),
            autoPauseEnabled = prefs[Keys.AUTO_PAUSE] ?: true,
            autoPauseSeconds = AutoPauseDelay.clamp(prefs[Keys.AUTO_PAUSE_SECONDS] ?: AutoPauseDelay.DEFAULT_SECONDS),
            privacyZones = com.startinsnow.gpstracker.core.privacy.PrivacyZones.fromJson(prefs[Keys.PRIVACY_ZONES]),
            stepSource = runCatching { StepSource.valueOf(prefs[Keys.STEP_SOURCE] ?: StepSource.GOOGLE_FIT.name) }.getOrDefault(StepSource.GOOGLE_FIT),
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
            webWifiOnly = prefs[Keys.WEB_WIFI_ONLY] ?: true,
            webUploadPhotos = prefs[Keys.WEB_UPLOAD_PHOTOS] ?: false,
            exportApplyPrivacy = prefs[Keys.EXPORT_PRIVACY] ?: false,
            deviceName = prefs[Keys.DEVICE_NAME] ?: ""
        )
    }.flowOn(Dispatchers.IO) // 解密要呼叫 Keystore，不放在主執行緒做

    suspend fun setGpsMode(mode: GpsMode) = context.dataStore.edit { it[Keys.GPS_MODE] = mode.name }
    suspend fun setAutoPauseEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.AUTO_PAUSE] = enabled }
    suspend fun setAutoPauseSeconds(seconds: Int) = context.dataStore.edit { it[Keys.AUTO_PAUSE_SECONDS] = AutoPauseDelay.clamp(seconds) }
    suspend fun setPrivacyZones(zones: List<com.startinsnow.gpstracker.core.privacy.PrivacyZone>) =
        context.dataStore.edit { it[Keys.PRIVACY_ZONES] = com.startinsnow.gpstracker.core.privacy.PrivacyZones.toJson(zones) }
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
    suspend fun setWebUploadPhotos(enabled: Boolean) = context.dataStore.edit { it[Keys.WEB_UPLOAD_PHOTOS] = enabled }
    suspend fun setExportApplyPrivacy(enabled: Boolean) = context.dataStore.edit { it[Keys.EXPORT_PRIVACY] = enabled }
    suspend fun setDeviceName(value: String) = context.dataStore.edit { it[Keys.DEVICE_NAME] = value.trim() }
}
