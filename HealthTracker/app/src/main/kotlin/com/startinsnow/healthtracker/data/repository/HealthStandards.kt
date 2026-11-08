package com.startinsnow.healthtracker.data.repository

import com.startinsnow.healthtracker.data.db.MeasureContext
import com.startinsnow.healthtracker.data.prefs.Sex
import java.time.Year

/**
 * 體重／體脂率／血糖的參考標準與判定（純計算，方便單元測試）。
 *
 * 來源與限制：
 * - BMI：台灣衛福部成人標準（18.5／24／27）與 WHO（18.5／25／30）。
 * - 體脂率：台灣常見的分齡參考值（18–39、40–59、60 歲以上，男女不同）。
 *   WHO 沒有訂定各年齡層體脂率門檻，這一項屬衛福部／國健署推廣與常見體脂計使用的參考值。
 * - 血糖：WHO／ADA 診斷參考（空腹 70–99、100–125 前期、≥126 糖尿病；餐後 <140、140–199 前期、≥200 糖尿病）。
 *   65 歲以上常有較寬鬆的控制目標，實際請依醫師指示。
 * - 未滿 18 歲的 BMI 與體脂率需使用兒童／青少年成長曲線，本程式不做成人判定。
 *
 * 這些判定僅供個人參考，不能取代醫師診斷。
 */
object HealthStandards {
    enum class Severity { LOW, NORMAL, HIGH, UNKNOWN }

    data class Assessment(val label: String, val detail: String, val severity: Severity)

    data class WeightRange(val minKg: Double, val maxKg: Double, val detail: String)

    fun ageFromBirthYear(birthYear: Int?, nowYear: Int = Year.now().value): Int? {
        val y = birthYear ?: return null
        val age = nowYear - y
        return age.takeIf { it in 0..130 }
    }

    fun bmi(weightKg: Double?, heightCm: Double?): Double? {
        val w = weightKg ?: return null
        val h = heightCm ?: return null
        if (w !in 20.0..300.0 || h !in 100.0..250.0) return null
        val m = h / 100.0
        return w / (m * m)
    }

    /** 衛福部 BMI 18.5–24 對應的標準體重範圍（公斤）。 */
    fun healthyWeightRange(heightCm: Double?, age: Int? = null): WeightRange? {
        val h = heightCm ?: return null
        if (h !in 100.0..250.0) return null
        val m = h / 100.0
        val min = 18.5 * m * m
        val max = 24.0 * m * m
        val older = if (age != null && age >= 65) "；65 歲以上可與醫師討論較寬鬆範圍（約 BMI 20–27）" else ""
        return WeightRange(min, max, "衛福部 BMI 18.5–24 對應 ${"%.1f".format(min)}–${"%.1f".format(max)} kg$older")
    }

    fun bmiAssessment(bmi: Double, age: Int? = null): Assessment {
        if (age != null && age < 18) {
            return Assessment("青少年", "未滿 18 歲請使用兒童／青少年 BMI 成長曲線，不以成人標準判定。", Severity.UNKNOWN)
        }
        val who = "WHO：18.5–25 正常、25–30 過重、≥30 肥胖"
        val older = if (age != null && age >= 65) "；65 歲以上可與醫師討論較寬鬆範圍" else ""
        return when {
            bmi < 18.5 -> Assessment("過瘦", "BMI $bmi < 18.5（衛福部成人標準；$who）$older", Severity.LOW)
            bmi < 24.0 -> Assessment("正常", "BMI $bmi 在 18.5–24 正常範圍（衛福部；$who）$older", Severity.NORMAL)
            bmi < 27.0 -> Assessment("過重", "BMI $bmi 在 24–27 過重範圍（衛福部；$who）$older", Severity.HIGH)
            else -> Assessment("肥胖", "BMI $bmi ≥ 27（衛福部；$who）$older", Severity.HIGH)
        }
    }

    /** 體脂率分齡判定；性別未指定或年齡未知時回傳 null（不猜）。 */
    fun bodyFatAssessment(percent: Double, sex: Sex, age: Int?): Assessment? {
        if (sex == Sex.UNSPECIFIED || age == null) return null
        if (age < 18) {
            return Assessment("青少年", "未滿 18 歲請使用兒童／青少年成長曲線，不以成人分齡標準判定。", Severity.UNKNOWN)
        }
        val band = fatBand(sex, age) ?: return null
        val detail = "${band.label} 正常 ${fmt(band.low)}–${fmt(band.normalHigh)}%；" +
            "過重 ${fmt(band.normalHigh)}–${fmt(band.overweightHigh)}%；肥胖 ≥${fmt(band.overweightHigh)}%（常見分齡參考）"
        return when {
            percent < band.low -> Assessment("過瘦", "體脂率 ${fmt(percent)}% 低於 ${fmt(band.low)}%；$detail", Severity.LOW)
            percent <= band.normalHigh -> Assessment("正常", "體脂率 ${fmt(percent)}% 在正常範圍；$detail", Severity.NORMAL)
            percent <= band.overweightHigh -> Assessment("過重", "體脂率 ${fmt(percent)}% 偏高；$detail", Severity.HIGH)
            else -> Assessment("肥胖", "體脂率 ${fmt(percent)}% 偏高；$detail", Severity.HIGH)
        }
    }

    fun glucoseAssessment(value: Double, context: MeasureContext?, age: Int? = null): Assessment {
        val band = glucoseBand(context)
        val older = if (age != null && age >= 65) "；65 歲以上控制目標常較寬鬆（空腹約 90–130），請依醫師指示" else ""
        val source = "WHO／ADA 參考"
        return when {
            value < band.low -> Assessment("低血糖", "血糖 ${fmt(value)} mg/dL < ${fmt(band.low)}（$source）$older", Severity.LOW)
            value <= band.normalHigh -> Assessment("正常", "血糖 ${fmt(value)} mg/dL 在 ${band.label}正常範圍 ${fmt(band.low)}–${fmt(band.normalHigh)}（$source）$older", Severity.NORMAL)
            value <= band.prediabetesHigh -> Assessment(
                "偏高（糖尿病前期）",
                "血糖 ${fmt(value)} mg/dL 在 ${band.label}偏高範圍 ${fmt(band.normalHigh + 1)}–${fmt(band.prediabetesHigh)}（$source）$older",
                Severity.HIGH,
            )
            else -> Assessment(
                "糖尿病範圍",
                "血糖 ${fmt(value)} mg/dL ≥ ${fmt(band.prediabetesHigh + 1)}（$source；請就醫確認）$older",
                Severity.HIGH,
            )
        }
    }

    fun weightAssessment(weightKg: Double, heightCm: Double?, age: Int? = null): Assessment? =
        bmi(weightKg, heightCm)?.let { bmiAssessment(it, age) }

    private data class FatBand(val low: Double, val normalHigh: Double, val overweightHigh: Double, val label: String)

    private fun fatBand(sex: Sex, age: Int): FatBand? = when (sex) {
        Sex.MALE -> when {
            age < 40 -> FatBand(8.0, 19.0, 24.0, "男性 18–39 歲")
            age < 60 -> FatBand(11.0, 21.0, 26.0, "男性 40–59 歲")
            else -> FatBand(13.0, 24.0, 29.0, "男性 60 歲以上")
        }
        Sex.FEMALE -> when {
            age < 40 -> FatBand(21.0, 32.0, 37.0, "女性 18–39 歲")
            age < 60 -> FatBand(23.0, 33.0, 38.0, "女性 40–59 歲")
            else -> FatBand(24.0, 35.0, 40.0, "女性 60 歲以上")
        }
        Sex.UNSPECIFIED -> null
    }

    private data class GlucoseBand(val low: Double, val normalHigh: Double, val prediabetesHigh: Double, val label: String)

    private fun glucoseBand(context: MeasureContext?): GlucoseBand = when (context) {
        MeasureContext.FASTING, MeasureContext.BEFORE_MEAL -> GlucoseBand(70.0, 99.0, 125.0, "空腹／餐前")
        MeasureContext.AFTER_MEAL -> GlucoseBand(70.0, 139.0, 199.0, "餐後")
        else -> GlucoseBand(70.0, 139.0, 199.0, "隨機／其他")
    }

    private fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v)
}
