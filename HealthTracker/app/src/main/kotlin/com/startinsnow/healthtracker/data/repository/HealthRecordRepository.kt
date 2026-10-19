package com.startinsnow.healthtracker.data.repository

import com.startinsnow.healthtracker.data.db.HealthRecord
import com.startinsnow.healthtracker.data.db.HealthRecordDao
import com.startinsnow.healthtracker.data.db.RecordSource
import com.startinsnow.healthtracker.data.db.RecordType
import com.startinsnow.healthtracker.data.db.SyncState
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
                source = RecordSource.MANUAL,
                note = note.trim(),
                syncState = SyncState.PENDING,
                createdAt = now,
                updatedAt = now,
            )
        )
        return null
    }

    suspend fun delete(id: String, now: Long = System.currentTimeMillis()) = dao.softDelete(id, now)
}
