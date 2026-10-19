package com.startinsnow.healthtracker.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 紀錄類型。value / value2 的意義依類型而定。 */
enum class RecordType(val label: String, val unit: String) {
    WEIGHT("體重", "kg"),
    BODY_FAT("體脂率", "%"),
    BLOOD_GLUCOSE("血糖", "mg/dL"),
    BLOOD_PRESSURE("血壓", "mmHg"), // value = 收縮壓、value2 = 舒張壓
}

/** 資料來源。第 1 階段只有手動；之後的 OCR／米家／同步來源在此擴充。 */
enum class RecordSource { MANUAL, OCR, MIJIA, IMPORT }

/** 同步狀態：本機優先，日後同步（第 4、6 天）靠它判斷哪些資料待上傳。 */
enum class SyncState { LOCAL_ONLY, PENDING, SYNCED }

/**
 * 一筆健康量測。
 * - id 使用 UUID 字串（由用戶端產生），多裝置同步時不會撞號，也能去重。
 * - 刪除採軟刪除（deletedAt），讓同步端能得知刪除事件。
 */
@Entity(
    tableName = "health_records",
    indices = [
        Index(value = ["type", "measuredAt"]),
        Index(value = ["syncState"]),
    ],
)
data class HealthRecord(
    @PrimaryKey val id: String,
    val type: RecordType,
    val value: Double,
    val value2: Double? = null,
    val measuredAt: Long, // epoch millis
    val source: RecordSource = RecordSource.MANUAL,
    val note: String = "",
    val syncState: SyncState = SyncState.LOCAL_ONLY,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    /** OCR 拍照紀錄的原始照片（App 私有目錄的絕對路徑），供日後回頭核對。 */
    val photoPath: String? = null,
)
