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

    @Query("SELECT * FROM health_records WHERE id = :id")
    suspend fun get(id: String): HealthRecord?

    @Query("UPDATE health_records SET deletedAt = :now, updatedAt = :now, syncState = :pending WHERE id = :id")
    suspend fun softDelete(id: String, now: Long, pending: SyncState = SyncState.PENDING)

    @Query("UPDATE health_records SET syncState = 'SYNCED' WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun markSynced(id: String, updatedAt: Long): Int

    @Query("SELECT * FROM health_records WHERE syncState != 'SYNCED'")
    suspend fun pendingSync(): List<HealthRecord>
}
