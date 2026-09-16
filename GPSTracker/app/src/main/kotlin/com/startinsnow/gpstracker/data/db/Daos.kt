package com.startinsnow.gpstracker.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.startinsnow.gpstracker.core.model.TrackStatus
import com.startinsnow.gpstracker.core.model.UploadStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: TrackEntity)

    @Update
    suspend fun update(track: TrackEntity)

    @Query("SELECT * FROM tracks WHERE trackId = :trackId")
    suspend fun getById(trackId: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE trackId = :trackId")
    fun observeById(trackId: String): Flow<TrackEntity?>

    @Query("SELECT * FROM tracks ORDER BY startTimeMs DESC")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE status = :status ORDER BY startTimeMs DESC LIMIT 1")
    suspend fun getMostRecentByStatus(status: TrackStatus): TrackEntity?

    @Query("SELECT * FROM tracks WHERE uploadStatus != :uploaded")
    suspend fun getPendingUpload(uploaded: UploadStatus = UploadStatus.UPLOADED): List<TrackEntity>

    /** Auto Cleanup 用：直接由 SQL 篩選（避免把所有 Track 載入記憶體）。 */
    @Query(
        "SELECT trackId FROM tracks WHERE status = :status " +
            "AND COALESCE(endTimeMs, startTimeMs) < :cutoffTimestampMs"
    )
    suspend fun getFinishedTrackIdsBefore(status: TrackStatus, cutoffTimestampMs: Long): List<String>

    @Query("DELETE FROM tracks WHERE trackId = :trackId")
    suspend fun deleteById(trackId: String)

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int
}

@Dao
interface TrackPointDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(point: TrackPointEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(points: List<TrackPointEntity>)

    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY timestampMs ASC")
    suspend fun getAllForTrack(trackId: String): List<TrackPointEntity>

    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY timestampMs ASC")
    fun observeForTrack(trackId: String): Flow<List<TrackPointEntity>>

    @Query("SELECT COUNT(*) FROM track_points WHERE trackId = :trackId")
    suspend fun countForTrack(trackId: String): Int

    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY timestampMs DESC LIMIT 1")
    suspend fun getLastForTrack(trackId: String): TrackPointEntity?

    @Query("DELETE FROM track_points WHERE trackId = :trackId")
    suspend fun deleteForTrack(trackId: String)
}

@Dao
interface PhotoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(photo: PhotoEntity)

    @Query("SELECT * FROM photos WHERE trackId = :trackId ORDER BY timestampMs ASC")
    suspend fun getAllForTrack(trackId: String): List<PhotoEntity>

    @Query("SELECT * FROM photos WHERE trackId = :trackId ORDER BY timestampMs ASC")
    fun observeForTrack(trackId: String): Flow<List<PhotoEntity>>

    @Query("SELECT COUNT(*) FROM photos WHERE trackId = :trackId")
    suspend fun countForTrack(trackId: String): Int

    @Query("DELETE FROM photos WHERE trackId = :trackId")
    suspend fun deleteForTrack(trackId: String)
}

@Dao
interface MovementSegmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(segment: MovementSegmentEntity): Long

    @Update
    suspend fun update(segment: MovementSegmentEntity)

    @Query("SELECT * FROM movement_segments WHERE trackId = :trackId ORDER BY startTimeMs ASC")
    suspend fun getAllForTrack(trackId: String): List<MovementSegmentEntity>

    @Query("SELECT * FROM movement_segments WHERE trackId = :trackId ORDER BY startTimeMs DESC LIMIT 1")
    suspend fun getLastForTrack(trackId: String): MovementSegmentEntity?

    @Query("DELETE FROM movement_segments WHERE trackId = :trackId")
    suspend fun deleteForTrack(trackId: String)
}

@Dao
interface GpsOutageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(outage: GpsOutageEntity): Long

    @Update
    suspend fun update(outage: GpsOutageEntity)

    @Query("SELECT * FROM gps_outages WHERE trackId = :trackId ORDER BY startTimestampMs ASC")
    suspend fun getAllForTrack(trackId: String): List<GpsOutageEntity>

    @Query("SELECT * FROM gps_outages WHERE trackId = :trackId AND endTimestampMs IS NULL LIMIT 1")
    suspend fun getOngoingForTrack(trackId: String): GpsOutageEntity?

    @Query("DELETE FROM gps_outages WHERE trackId = :trackId")
    suspend fun deleteForTrack(trackId: String)

    @Delete
    suspend fun delete(outage: GpsOutageEntity)
}
