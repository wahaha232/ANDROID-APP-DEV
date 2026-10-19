package com.startinsnow.healthtracker.data.repository

import com.startinsnow.healthtracker.data.db.HealthRecord
import com.startinsnow.healthtracker.data.db.HealthRecordDao
import com.startinsnow.healthtracker.data.db.RecordSource
import com.startinsnow.healthtracker.data.db.RecordType
import com.startinsnow.healthtracker.data.db.SyncState
import com.startinsnow.healthtracker.ocr.PhotoStore
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class HealthRecordRepository(private val dao: HealthRecordDao) {
    fun observeAll(): Flow<List<HealthRecord>> = dao.observeAll()

    /** 新增手動紀錄；驗證失敗回傳錯誤訊息，成功回傳 null。 */
    suspend fun addManual(
        type: RecordType,
        value: Double?,
        value2: Double?,
        measuredAt: Long,
        note: String,
        source: RecordSource = RecordSource.MANUAL,
        photoPath: String? = null,
        now: Long = System.currentTimeMillis(),
    ): String? {
        RecordValidator.validate(type, value, value2)?.let { return it }
        dao.upsert(
            HealthRecord(
                id = UUID.randomUUID().toString(),
                type = type,
                value = value!!,
                value2 = if (type == RecordType.BLOOD_PRESSURE) value2 else null,
                measuredAt = measuredAt,
                source = source,
                note = note.trim(),
                syncState = SyncState.PENDING,
                createdAt = now,
                updatedAt = now,
                photoPath = photoPath,
            )
        )
        return null
    }

    /** 軟刪除並一併刪掉原始照片檔（同步端只需要知道刪除事件，不需要照片）。 */
    suspend fun delete(id: String, now: Long = System.currentTimeMillis()) {
        dao.get(id)?.photoPath?.let { PhotoStore.delete(it) }
        dao.softDelete(id, now)
    }
}
