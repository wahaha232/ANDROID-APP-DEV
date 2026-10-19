package com.startinsnow.healthtracker.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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
        )
    }

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
