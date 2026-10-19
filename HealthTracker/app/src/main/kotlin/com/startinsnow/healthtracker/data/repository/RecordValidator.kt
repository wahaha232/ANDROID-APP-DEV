package com.startinsnow.healthtracker.data.repository

import com.startinsnow.healthtracker.data.db.RecordType

/** 輸入範圍檢查（防手滑，例如把 70.5 打成 705）。回傳 null 表示通過，否則為錯誤訊息。 */
object RecordValidator {
    fun validate(type: RecordType, value: Double?, value2: Double?): String? {
        if (value == null || value.isNaN()) return "請輸入數值"
        return when (type) {
            RecordType.WEIGHT -> range("體重", value, 20.0, 300.0, "kg")
            RecordType.BODY_FAT -> range("體脂率", value, 2.0, 70.0, "%")
            RecordType.BLOOD_GLUCOSE -> range("血糖", value, 20.0, 800.0, "mg/dL")
            RecordType.BLOOD_PRESSURE -> {
                if (value2 == null || value2.isNaN()) return "請輸入舒張壓"
                range("收縮壓", value, 50.0, 260.0, "mmHg")
                    ?: range("舒張壓", value2, 30.0, 160.0, "mmHg")
                    ?: if (value2 >= value) "舒張壓不應大於或等於收縮壓" else null
            }
        }
    }

    /** 目標範圍（mg/dL）：兩個都不填＝不設定；要填就兩個一起填，且下限小於上限。回傳 null 表示通過。 */
    fun validateGlucoseRange(low: Double?, high: Double?): String? {
        if (low == null && high == null) return null
        if (low == null || high == null) return "目標範圍請同時填下限與上限"
        if (low < 20.0 || high > 800.0) return "目標範圍請填 20–800 mg/dL"
        if (low >= high) return "下限必須小於上限"
        return null
    }

    /** 測量時間不能在未來（超過容許誤差）。回傳 null 表示通過。 */
    fun validateTime(measuredAt: Long, now: Long): String? =
        if (MeasureTime.isFuture(measuredAt, now)) "測量時間不能晚於現在" else null

    private fun range(name: String, v: Double, min: Double, max: Double, unit: String): String? =
        if (v < min || v > max) "$name 應介於 $min–$max $unit" else null
}
