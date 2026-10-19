package com.startinsnow.healthtracker.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HealthRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: HealthRecord)

    /** 匯入用：同 id 已存在（含已被使用者刪除的）就略過，回傳 -1。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(record: HealthRecord): Long

    @Query("SELECT * FROM health_records WHERE deletedAt IS NULL ORDER BY measuredAt DESC")
    fun observeAll(): Flow<List<HealthRecord>>

    @Query("SELECT * FROM health_records WHERE deletedAt IS NULL AND type = :type ORDER BY measuredAt DESC")
    fun observeByType(type: RecordType): Flow<List<HealthRecord>>

    /** 備份用：含已刪除（軟刪除）的全部紀錄，還原時才不會讓刪除的資料復活。 */
    @Query("SELECT * FROM health_records")
    suspend fun all(): List<HealthRecord>

    @Query("SELECT * FROM health_records WHERE id = :id")
    suspend fun get(id: String): HealthRecord?

    @Query("UPDATE health_records SET deletedAt = :now, updatedAt = :now, syncState = :pending WHERE id = :id")
    suspend fun softDelete(id: String, now: Long, pending: SyncState = SyncState.PENDING)

    /** 同一次量測的近似重複判斷：同類型、數值幾乎相同、時間差在區間內（含已刪除的，避免刪掉的又被別的來源帶回）。 */
    @Query(
        "SELECT COUNT(*) FROM health_records WHERE type = :type AND ABS(value - :value) < 0.05 " +
            "AND ((value2 IS NULL AND :value2 IS NULL) OR ABS(IFNULL(value2, 0) - IFNULL(:value2, 0)) < 0.5) " +
            "AND measuredAt BETWEEN :from AND :to"
    )
    suspend fun countNearby(type: RecordType, value: Double, value2: Double?, from: Long, to: Long): Int

    @Query("UPDATE health_records SET syncState = 'SYNCED' WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markSynced(id: String, updatedAt: Long): Int

    /** 待上傳的筆數（含已刪除、尚未同步的），給設定頁顯示。 */
    @Query("SELECT COUNT(*) FROM health_records WHERE syncState != 'SYNCED'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM health_records WHERE syncState != 'SYNCED'")
    suspend fun pendingSync(): List<HealthRecord>
}
