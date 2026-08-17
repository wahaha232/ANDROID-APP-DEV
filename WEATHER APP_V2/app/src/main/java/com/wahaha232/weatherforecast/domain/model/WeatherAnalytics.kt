// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/domain/model/WeatherAnalytics.kt
package com.wahaha232.weatherforecast.domain.model

data class BeaufortScale(val scale: Int, val descriptionZh: String)

data class AqiCategory(val labelZh: String, val adviceZh: String, val severityLevel: Int)

data class MoonPhase(val nameZh: String, val icon: String, val phaseFraction: Double)

data class PhotographyTimes(
    val goldenMorning: String,
    val goldenEvening: String,
    val blueMorning: String,
    val blueEvening: String
)

data class AllergenInfo(val nameZh: String, val levelZh: String, val severityLevel: Int)

/**
 * 天氣衍生數據的純商業邏輯運算（風級、AQI 分級、月相、攝影黃金/藍色時段、過敏原推估）。
 * 全部為無 Android 依賴的純函式，方便單元測試，也符合 Clean Architecture 對 Domain 層的要求。
 */
object WeatherAnalytics {

    /** 蒲福風級（Beaufort Scale），輸入風速需為 km/h */
    fun getBeaufortScale(windSpeedKmh: Double): BeaufortScale = when {
        windSpeedKmh < 1 -> BeaufortScale(0, "0 級（無風）")
        windSpeedKmh <= 5 -> BeaufortScale(1, "1 級（軟風）")
        windSpeedKmh <= 11 -> BeaufortScale(2, "2 級（輕風）")
        windSpeedKmh <= 19 -> BeaufortScale(3, "3 級（微風）")
        windSpeedKmh <= 28 -> BeaufortScale(4, "4 級（和風）")
        windSpeedKmh <= 38 -> BeaufortScale(5, "5 級（清風）")
        windSpeedKmh <= 49 -> BeaufortScale(6, "6 級（強風）")
        windSpeedKmh <= 61 -> BeaufortScale(7, "7 級（疾風）")
        windSpeedKmh <= 74 -> BeaufortScale(8, "8 級（大風）")
        windSpeedKmh <= 88 -> BeaufortScale(9, "9 級（烈風）")
        windSpeedKmh <= 102 -> BeaufortScale(10, "10 級（狂風）")
        windSpeedKmh <= 117 -> BeaufortScale(11, "11 級（暴風）")
        else -> BeaufortScale(12, "12 級（颶風）")
    }

    /** 風向角度 -> 16 方位中文名稱 */
    fun getWindDirectionLabel(degrees: Int): String {
        val directions = listOf(
            "北風 (N)", "東北北 (NNE)", "東北 (NE)", "東北東 (ENE)",
            "東風 (E)", "東南東 (ESE)", "東南 (SE)", "東南南 (SSE)",
            "南風 (S)", "西南南 (SSW)", "西南 (SW)", "西南西 (WSW)",
            "西風 (W)", "西北西 (WNW)", "西北 (NW)", "西北北 (NNW)"
        )
        val index = (Math.round(degrees / 22.5f)).mod(16)
        return directions[index]
    }

    /** US AQI 分級與建議 */
    fun getAqiCategory(usAqi: Int?): AqiCategory = when {
        usAqi == null -> AqiCategory("無資料", "暫無空氣品質數值", 0)
        usAqi <= 50 -> AqiCategory("良好", "空氣品質極佳，適合各項戶外活動。", 1)
        usAqi <= 100 -> AqiCategory("普通", "空氣品質尚可，極少數敏感體質者應斟酌戶外活動。", 2)
        usAqi <= 150 -> AqiCategory("對敏感族群不健康", "老人、小孩及心肺疾病患者宜減少長時間劇烈戶外運動。", 3)
        usAqi <= 200 -> AqiCategory("不健康", "所有人健康皆可能受影響，外出建議配戴防護口罩。", 4)
        usAqi <= 300 -> AqiCategory("非常不健康", "健康警報，應盡量留在室內並關閉門窗。", 5)
        else -> AqiCategory("危害", "緊急健康風險，應完全避免任何戶外活動。", 6)
    }

    /** 紫外線指數分級文字 */
    fun getUvCategoryLabel(uvIndex: Double): String = when {
        uvIndex < 3 -> "低"
        uvIndex < 6 -> "中"
        uvIndex < 8 -> "高"
        uvIndex < 11 -> "極高"
        else -> "危險"
    }

    /**
     * 依指定時間推算月相（朔望週期演算法，基準新月時間：2000-01-06T18:14:00Z）。
     */
    fun getMoonPhase(epochMillis: Long): MoonPhase {
        val knownNewMoonMillis = 947182440000L // 2000-01-06T18:14:00Z
        val synodicMonthMillis = (29.53058867 * 24 * 60 * 60 * 1000).toLong()
        val elapsed = epochMillis - knownNewMoonMillis
        val phaseValue = ((elapsed % synodicMonthMillis) + synodicMonthMillis) % synodicMonthMillis
        val phaseFraction = phaseValue.toDouble() / synodicMonthMillis

        return when {
            phaseFraction < 0.03 || phaseFraction > 0.97 -> MoonPhase("新月", "\uD83C\uDF11", phaseFraction)
            phaseFraction < 0.22 -> MoonPhase("蛾眉月", "\uD83C\uDF18", phaseFraction)
            phaseFraction < 0.28 -> MoonPhase("上弦月", "\uD83C\uDF13", phaseFraction)
            phaseFraction < 0.47 -> MoonPhase("盈凸月", "\uD83C\uDF14", phaseFraction)
            phaseFraction < 0.53 -> MoonPhase("滿月", "\uD83C\uDF15", phaseFraction)
            phaseFraction < 0.72 -> MoonPhase("虧凸月", "\uD83C\uDF16", phaseFraction)
            phaseFraction < 0.78 -> MoonPhase("下弦月", "\uD83C\uDF17", phaseFraction)
            else -> MoonPhase("殘月", "\uD83C\uDF12", phaseFraction)
        }
    }

    /**
     * 依日出/日落 ISO 時間（本地時間，格式如 "2026-08-17T05:28"）推算攝影黃金/藍色時段。
     * 若解析失敗則回退為預設時間，確保 UI 永遠有值可顯示。
     */
    fun getPhotographyTimes(sunriseIso: String?, sunsetIso: String?): PhotographyTimes {
        val sunriseMinutes = parseMinutesOfDay(sunriseIso) ?: (5 * 60 + 28)
        val sunsetMinutes = parseMinutesOfDay(sunsetIso) ?: (18 * 60 + 30)

        fun fmt(totalMinutes: Int): String {
            val normalized = ((totalMinutes % 1440) + 1440) % 1440
            val h = normalized / 60
            val m = normalized % 60
            return "%02d:%02d".format(h, m)
        }

        return PhotographyTimes(
            goldenMorning = "${fmt(sunriseMinutes - 13)} - ${fmt(sunriseMinutes + 18)}",
            goldenEvening = "${fmt(sunsetMinutes - 30)} - ${fmt(sunsetMinutes)}",
            blueMorning = "${fmt(sunriseMinutes - 24)} - ${fmt(sunriseMinutes - 14)}",
            blueEvening = "${fmt(sunsetMinutes + 16)} - ${fmt(sunsetMinutes + 25)}"
        )
    }

    private fun parseMinutesOfDay(iso: String?): Int? {
        if (iso.isNullOrBlank()) return null
        return try {
            // 格式："2026-08-17T05:28"，取 "T" 之後的 "HH:mm"
            val timePart = iso.substringAfter("T")
            val hour = timePart.substring(0, 2).toInt()
            val minute = timePart.substring(3, 5).toInt()
            hour * 60 + minute
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 依濕度與空氣品質推估過敏原（灰塵/皮屑、樹木花粉、青草花粉）等級。
     * 此為簡化的環境經驗法則，非醫療級花粉監測數據。
     */
    fun calculateAllergenLevels(humidity: Int, usAqi: Int?): List<AllergenInfo> {
        val aqi = usAqi ?: 50
        val dust = when {
            humidity > 80 && aqi < 30 -> AllergenInfo("灰塵和皮屑", "低", 1)
            humidity > 70 && aqi < 40 -> AllergenInfo("灰塵和皮屑", "中", 2)
            else -> AllergenInfo("灰塵和皮屑", "極高", 3)
        }
        return listOf(
            dust,
            AllergenInfo("樹木花粉", "低", 1),
            AllergenInfo("青草花粉", "低", 1)
        )
    }
}
