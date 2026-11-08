package com.startinsnow.healthtracker.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.startinsnow.healthtracker.data.db.HealthRecord
import com.startinsnow.healthtracker.data.db.RecordType
import com.startinsnow.healthtracker.data.prefs.UserSettings
import com.startinsnow.healthtracker.data.repository.HealthStandards
import java.time.Year

/** 依個人設定（性別、出生年、身高）判定這筆紀錄是否符合參考標準；資料不足回傳 null。 */
internal fun assessmentFor(r: HealthRecord, user: UserSettings, nowYear: Int = Year.now().value): HealthStandards.Assessment? {
    val age = HealthStandards.ageFromBirthYear(user.birthYear, nowYear)
    return when (r.type) {
        RecordType.WEIGHT -> HealthStandards.weightAssessment(r.value, user.heightCm, age)
        RecordType.BODY_FAT -> HealthStandards.bodyFatAssessment(r.value, user.sex, age)
        RecordType.BLOOD_GLUCOSE -> HealthStandards.glucoseAssessment(r.value, r.context, age)
        else -> null
    }
}

/** 總覽的標準判定：用一段期間的平均值判定，而不是只看最近一筆。 */
internal fun assessmentForAverage(
    type: RecordType,
    average: Double,
    user: UserSettings,
    context: com.startinsnow.healthtracker.data.db.MeasureContext? = null,
    nowYear: Int = Year.now().value,
): HealthStandards.Assessment? {
    val age = HealthStandards.ageFromBirthYear(user.birthYear, nowYear)
    return when (type) {
        RecordType.WEIGHT -> HealthStandards.weightAssessment(average, user.heightCm, age)
        RecordType.BODY_FAT -> HealthStandards.bodyFatAssessment(average, user.sex, age)
        RecordType.BLOOD_GLUCOSE -> HealthStandards.glucoseAssessment(average, context, age)
        else -> null
    }
}

@Composable
internal fun assessmentColor(severity: HealthStandards.Severity): Color {
    val dark = isSystemInDarkTheme()
    return when (severity) {
        HealthStandards.Severity.LOW -> if (dark) Color(0xFFFFB74D) else Color(0xFFB26A00)
        HealthStandards.Severity.NORMAL -> if (dark) Color(0xFF81C784) else Color(0xFF2E7D32)
        HealthStandards.Severity.HIGH -> MaterialTheme.colorScheme.error
        HealthStandards.Severity.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/** 一行判定標籤＋標準說明；[title] 用來區分「此筆判定」與「平均判定」。 */
@Composable
internal fun AssessmentLine(a: HealthStandards.Assessment, modifier: Modifier = Modifier, title: String = "此筆判定") {
    Column(modifier) {
        Text(
            "${a.label}（$title）",
            color = assessmentColor(a.severity),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            a.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
