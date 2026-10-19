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

    private fun range(name: String, v: Double, min: Double, max: Double, unit: String): String? =
        if (v < min || v > max) "$name 應介於 $min–$max $unit" else null
}
