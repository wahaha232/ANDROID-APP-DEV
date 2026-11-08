package com.startinsnow.healthtracker.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class Sex { UNSPECIFIED, MALE, FEMALE }

/** 個人設定（單人使用）。未填的欄位為 null / 預設值。 */
data class UserSettings(
    val displayName: String = "",
    val sex: Sex = Sex.UNSPECIFIED,
    val birthYear: Int? = null,
    val heightCm: Double? = null,
    val targetWeightKg: Double? = null,
    /** 血糖目標範圍（mg/dL），由使用者依醫師建議自行設定；App 只用來畫範圍帶與統計，不判斷好壞。 */
    val glucoseLow: Double? = null,
    val glucoseHigh: Double? = null,
    /** 量測提醒：啟用、每天的提醒時間（當天第幾分鐘，0–1439）、今天已記錄血糖就不提醒。 */
    val reminderEnabled: Boolean = false,
    val reminderTimes: List<Int> = emptyList(),
    val reminderSkipIfRecorded: Boolean = true,
    /** 開啟 App 需要指紋 / 臉部 / 手機 PIN。 */
    val appLock: Boolean = false,
    /** 新增紀錄的樣式：true = 底部面板（預設）、false = 原本的對話框。 */
    val addAsSheet: Boolean = true,
    /** 總覽圖表的呈現方式：0 趨勢、1 日曆、2 時段。 */
    val overviewChart: Int = 0,
    /** App 自動檢查更新的間隔（小時）；0 = 只手動檢查。 */
    val updateIntervalHours: Int = 0,
) {
    /** BMI；身高或體重不合理時回傳 null。 */
    fun bmi(weightKg: Double): Double? {
        val h = heightCm ?: return null
        if (h !in 100.0..250.0 || weightKg !in 20.0..300.0) return null
        val m = h / 100.0
        return weightKg / (m * m)
    }
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "user_settings")

class UserSettingsRepository(private val context: Context) {
    private object Keys {
        val name = stringPreferencesKey("display_name")
        val sex = stringPreferencesKey("sex")
        val birthYear = intPreferencesKey("birth_year")
        val height = doublePreferencesKey("height_cm")
        val target = doublePreferencesKey("target_weight_kg")
        val glucoseLow = doublePreferencesKey("glucose_low")
        val glucoseHigh = doublePreferencesKey("glucose_high")
        val reminderEnabled = booleanPreferencesKey("reminder_enabled")
        val reminderTimes = stringPreferencesKey("reminder_times")
        val reminderSkip = booleanPreferencesKey("reminder_skip_if_recorded")
        val appLock = booleanPreferencesKey("app_lock")
        val addAsSheet = booleanPreferencesKey("add_as_sheet")
        val overviewChart = intPreferencesKey("overview_chart")
        val updateInterval = intPreferencesKey("update_interval_hours")
    }

    val settings: Flow<UserSettings> = context.settingsStore.data.map { p ->
        UserSettings(
            displayName = p[Keys.name].orEmpty(),
            sex = p[Keys.sex]?.let { runCatching { Sex.valueOf(it) }.getOrNull() } ?: Sex.UNSPECIFIED,
            birthYear = p[Keys.birthYear],
            heightCm = p[Keys.height],
            targetWeightKg = p[Keys.target],
            glucoseLow = p[Keys.glucoseLow],
            glucoseHigh = p[Keys.glucoseHigh],
            reminderEnabled = p[Keys.reminderEnabled] ?: false,
            reminderTimes = parseTimes(p[Keys.reminderTimes]),
            reminderSkipIfRecorded = p[Keys.reminderSkip] ?: true,
            appLock = p[Keys.appLock] ?: false,
            addAsSheet = p[Keys.addAsSheet] ?: true,
            overviewChart = (p[Keys.overviewChart] ?: 0).coerceIn(0, 2),
            updateIntervalHours = p[Keys.updateInterval] ?: 0,
        )
    }

    suspend fun saveUpdateInterval(hours: Int) {
        context.settingsStore.edit { it[Keys.updateInterval] = hours }
    }

    suspend fun saveAddAsSheet(value: Boolean) {
        context.settingsStore.edit { it[Keys.addAsSheet] = value }
    }

    suspend fun saveOverviewChart(mode: Int) {
        context.settingsStore.edit { it[Keys.overviewChart] = mode.coerceIn(0, 2) }
    }

    /** 只改提醒設定，不動個人資料欄位。 */
    suspend fun saveReminders(enabled: Boolean, times: List<Int>, skipIfRecorded: Boolean) {
        context.settingsStore.edit { p ->
            p[Keys.reminderEnabled] = enabled
            p[Keys.reminderTimes] = times.distinct().sorted().joinToString(",")
            p[Keys.reminderSkip] = skipIfRecorded
        }
    }

    suspend fun saveAppLock(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.appLock] = enabled }
    }

    private fun parseTimes(raw: String?): List<Int> =
        raw.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 0..1439 }.distinct().sorted()

    suspend fun save(s: UserSettings) {
        context.settingsStore.edit { p ->
            p[Keys.name] = s.displayName
            p[Keys.sex] = s.sex.name
            s.birthYear?.let { p[Keys.birthYear] = it } ?: p.remove(Keys.birthYear)
            s.heightCm?.let { p[Keys.height] = it } ?: p.remove(Keys.height)
            s.targetWeightKg?.let { p[Keys.target] = it } ?: p.remove(Keys.target)
            s.glucoseLow?.let { p[Keys.glucoseLow] = it } ?: p.remove(Keys.glucoseLow)
            s.glucoseHigh?.let { p[Keys.glucoseHigh] = it } ?: p.remove(Keys.glucoseHigh)
        }
    }
}
