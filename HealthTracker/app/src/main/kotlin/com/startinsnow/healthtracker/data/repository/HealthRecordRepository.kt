package com.startinsnow.healthtracker.data.repository

import com.startinsnow.healthtracker.backup.RestoreAction
import com.startinsnow.healthtracker.backup.planRestore
import com.startinsnow.healthtracker.data.db.HealthRecord
import com.startinsnow.healthtracker.data.db.HealthRecordDao
import com.startinsnow.healthtracker.data.db.MeasureContext
import com.startinsnow.healthtracker.data.db.RecordSource
import com.startinsnow.healthtracker.data.db.RecordType
import com.startinsnow.healthtracker.data.db.SyncState
import com.startinsnow.healthtracker.ocr.PhotoStore
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import java.util.UUID

data class ImportResult(val inserted: Int, val skipped: Int)

data class RestoreResult(val inserted: Int, val updated: Int, val skipped: Int)

class HealthRecordRepository(private val dao: HealthRecordDao, private val db: RoomDatabase? = null) {
    /** 批次寫入合併成單一交易：全有或全無（中途失敗不留半套資料），畫面只重繪一次。 */
    private suspend fun <T> inTransaction(block: suspend () -> T): T = db?.withTransaction { block() } ?: block()

    private companion object {
        const val DUPLICATE_WINDOW_MS = 2 * 60_000L
    }

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
        context: MeasureContext? = null,
        now: Long = System.currentTimeMillis(),
    ): String? {
        RecordValidator.validate(type, value, value2)?.let { return it }
        RecordValidator.validateTime(measuredAt, now)?.let { return it }
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
                context = if (type == RecordType.BLOOD_GLUCOSE) context else null,
            )
        )
        return null
    }

    /**
     * 修改既有紀錄（數值、測量時間、備註）。類型、來源與照片不變。
     * 更新時間一定比原本大，並標為待同步，雲端與其他裝置才會以這次修改為準。
     */
    suspend fun update(
        id: String,
        value: Double?,
        value2: Double?,
        measuredAt: Long,
        note: String,
        context: MeasureContext? = null,
        now: Long = System.currentTimeMillis(),
    ): String? {
        val existing = dao.get(id) ?: return "找不到這筆紀錄"
        if (existing.deletedAt != null) return "這筆紀錄已被刪除"
        RecordValidator.validate(existing.type, value, value2)?.let { return it }
        RecordValidator.validateTime(measuredAt, now)?.let { return it }
        dao.upsert(
            existing.copy(
                value = value!!,
                value2 = if (existing.type == RecordType.BLOOD_PRESSURE) value2 else null,
                measuredAt = measuredAt,
                note = note.trim(),
                context = if (existing.type == RecordType.BLOOD_GLUCOSE) context else null,
                syncState = SyncState.PENDING,
                updatedAt = maxOf(now, existing.updatedAt + 1),
            )
        )
        return null
    }

    /** 批次匯入（Health Connect／CSV）。id 是確定性的，所以重複匯入、或匯入使用者已刪除的紀錄都不會重複出現。 */
    suspend fun importRecords(records: List<HealthRecord>): ImportResult {
        return inTransaction {
        var inserted = 0
        for (r in records) {
            // 不同來源（米家畫面、Health Connect、CSV）可能是同一次量測：同類型、同數值、時間差 2 分鐘內視為重複。
            val duplicate = dao.countNearby(r.type, r.value, r.value2, r.measuredAt - DUPLICATE_WINDOW_MS, r.measuredAt + DUPLICATE_WINDOW_MS) > 0
            if (!duplicate && dao.insertIgnore(r) != -1L) inserted++
        }
        ImportResult(inserted = inserted, skipped = records.size - inserted)
        }
    }

    suspend fun allForBackup(): List<HealthRecord> = dao.all()

    /** 還原備份：新增沒有的、較新的覆蓋舊的、其餘略過。還原的資料一律標為待同步。 */
    suspend fun restore(incoming: List<HealthRecord>): RestoreResult {
        return inTransaction {
        var inserted = 0
        var updated = 0
        var skipped = 0
        for (r in incoming) {
            val existing = dao.get(r.id)
            when (planRestore(existing, r)) {
                RestoreAction.INSERT -> { dao.upsert(r); inserted++ }
                RestoreAction.UPDATE -> { dao.upsert(r.copy(photoPath = existing?.photoPath)); updated++ }
                RestoreAction.SKIP -> skipped++
            }
        }
        RestoreResult(inserted, updated, skipped)
        }
    }

    /** 軟刪除並一併刪掉原始照片檔（同步端只需要知道刪除事件，不需要照片）。 */
    suspend fun delete(id: String, now: Long = System.currentTimeMillis()) {
        dao.get(id)?.photoPath?.let { PhotoStore.delete(it) }
        dao.softDelete(id, now)
    }
}
