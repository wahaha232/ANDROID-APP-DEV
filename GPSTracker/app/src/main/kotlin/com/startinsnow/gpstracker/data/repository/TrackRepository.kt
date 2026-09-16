package com.startinsnow.gpstracker.data.repository

import com.startinsnow.gpstracker.core.geo.GeoMath
import com.startinsnow.gpstracker.core.model.FilteredLocation
import com.startinsnow.gpstracker.core.model.MovementMode
import com.startinsnow.gpstracker.core.model.PhotoType
import com.startinsnow.gpstracker.core.model.PointReliability
import com.startinsnow.gpstracker.core.model.TrackStatus
import com.startinsnow.gpstracker.core.model.UploadStatus
import com.startinsnow.gpstracker.data.db.GpsOutageDao
import com.startinsnow.gpstracker.data.db.GpsOutageEntity
import com.startinsnow.gpstracker.data.db.MovementSegmentDao
import com.startinsnow.gpstracker.data.db.MovementSegmentEntity
import com.startinsnow.gpstracker.data.db.PhotoDao
import com.startinsnow.gpstracker.data.db.PhotoEntity
import com.startinsnow.gpstracker.data.db.TrackDao
import com.startinsnow.gpstracker.data.db.TrackEntity
import com.startinsnow.gpstracker.data.db.TrackPointDao
import com.startinsnow.gpstracker.data.db.TrackPointEntity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Local-first 資料存取層。所有寫入都先落地 Room，之後才可能觸發 Google Drive 同步（規格 6/58）。
 * GPS Engine（Service）只透過這個 Repository 存取資料庫，不直接碰 DAO，方便日後替換儲存方式。
 */
class TrackRepository(
    private val trackDao: TrackDao,
    private val trackPointDao: TrackPointDao,
    private val photoDao: PhotoDao,
    private val movementSegmentDao: MovementSegmentDao,
    private val gpsOutageDao: GpsOutageDao,
    private val filesDir: File,
    /** 相機照片根目錄（external files dir），刪除 Track 時要一起清掉避免孤兒檔案。 */
    private val photoRootDir: File? = null
) {
    fun observeTracks(): Flow<List<TrackEntity>> = trackDao.observeAll()
    fun observeTrack(trackId: String): Flow<TrackEntity?> = trackDao.observeById(trackId)
    fun observePoints(trackId: String): Flow<List<TrackPointEntity>> = trackPointDao.observeForTrack(trackId)
    fun observePhotos(trackId: String): Flow<List<PhotoEntity>> = photoDao.observeForTrack(trackId)

    suspend fun getTrack(trackId: String): TrackEntity? = trackDao.getById(trackId)

    /** Auto Cleanup 用：取得指定時間點之前已完成的 Track ID（SQL 端篩選）。 */
    suspend fun getFinishedTrackIdsBefore(status: TrackStatus, cutoffTimestampMs: Long): List<String> =
        trackDao.getFinishedTrackIdsBefore(status, cutoffTimestampMs)
    suspend fun getLastPoint(trackId: String) = trackPointDao.getLastForTrack(trackId)
    suspend fun getPoints(trackId: String): List<TrackPointEntity> = trackPointDao.getAllForTrack(trackId)
    suspend fun getPhotos(trackId: String): List<PhotoEntity> = photoDao.getAllForTrack(trackId)
    suspend fun getSegments(trackId: String): List<MovementSegmentEntity> = movementSegmentDao.getAllForTrack(trackId)
    suspend fun getOutages(trackId: String): List<GpsOutageEntity> = gpsOutageDao.getAllForTrack(trackId)

    /** App 啟動時呼叫，找出上次未正常結束（Crash / 被系統回收）的 Track，供 Crash Recovery 使用（規格 57）。 */
    suspend fun findRecoverableTrack(): TrackEntity? =
        trackDao.getMostRecentByStatus(TrackStatus.RECORDING) ?: trackDao.getMostRecentByStatus(TrackStatus.PAUSED)

    suspend fun createTrack(startSample: com.startinsnow.gpstracker.core.model.LocationSample?): TrackEntity {
        val track = TrackEntity(
            trackId = UUID.randomUUID().toString(),
            startTimeMs = System.currentTimeMillis(),
            endTimeMs = null,
            status = TrackStatus.RECORDING,
            distanceMeters = 0.0,
            durationMs = 0L,
            avgSpeedMps = 0.0,
            maxSpeedMps = 0.0,
            stepCount = 0L,
            dominantMode = MovementMode.UNKNOWN,
            uploadStatus = UploadStatus.NOT_UPLOADED,
            gpsPointCount = 0,
            photoCount = 0,
            maxAltitudeMeters = null,
            minAltitudeMeters = null,
            totalAscentMeters = 0.0,
            totalDescentMeters = 0.0,
            startLatitude = startSample?.latitude,
            startLongitude = startSample?.longitude,
            endLatitude = null,
            endLongitude = null,
            modeDistributionJson = "{}"
        )
        trackDao.insert(track)
        return track
    }

    suspend fun appendPoint(filtered: FilteredLocation, movementMode: MovementMode): Long {
        val sample = filtered.sample
        val entity = TrackPointEntity(
            trackId = sample.trackId,
            latitude = sample.latitude,
            longitude = sample.longitude,
            timestampMs = sample.timestampMs,
            elapsedRealtimeMs = sample.elapsedRealtimeMs,
            speedMps = sample.speedMps,
            altitudeMeters = sample.altitudeMeters,
            accuracyMeters = sample.accuracyMeters,
            provider = sample.provider.name,
            bearingDegrees = sample.bearingDegrees,
            reliability = filtered.reliability.name,
            quality = filtered.quality.name,
            movementMode = movementMode
        )
        return trackPointDao.insert(entity)
    }

    suspend fun startOutageIfNeeded(trackId: String, timestampMs: Long) {
        val ongoing = gpsOutageDao.getOngoingForTrack(trackId)
        if (ongoing == null) {
            gpsOutageDao.insert(GpsOutageEntity(trackId = trackId, startTimestampMs = timestampMs, endTimestampMs = null))
        }
    }

    suspend fun endOutageIfNeeded(trackId: String, timestampMs: Long) {
        val ongoing = gpsOutageDao.getOngoingForTrack(trackId) ?: return
        gpsOutageDao.update(ongoing.copy(endTimestampMs = timestampMs))
    }

    suspend fun startMovementSegment(trackId: String, mode: MovementMode, timestampMs: Long) {
        val last = movementSegmentDao.getLastForTrack(trackId)
        if (last != null && last.endTimeMs == null) {
            if (last.mode == mode) return // 模式沒變，延續同一段
            movementSegmentDao.update(last.copy(endTimeMs = timestampMs))
        }
        movementSegmentDao.insert(MovementSegmentEntity(trackId = trackId, mode = mode, startTimeMs = timestampMs, endTimeMs = null))
    }

    suspend fun closeOpenMovementSegment(trackId: String, timestampMs: Long) {
        val last = movementSegmentDao.getLastForTrack(trackId)
        if (last != null && last.endTimeMs == null) {
            movementSegmentDao.update(last.copy(endTimeMs = timestampMs))
        }
    }

    /** 定期（非每個 GPS point）快照即時統計，避免每秒觸發多個 DB Transaction（規格 92/93）。 */
    suspend fun updateLiveStats(
        trackId: String,
        distanceMeters: Double,
        avgSpeedMps: Double,
        maxSpeedMps: Double,
        stepCount: Long,
        gpsPointCount: Int,
        durationMs: Long
    ) {
        val track = trackDao.getById(trackId) ?: return
        trackDao.update(
            track.copy(
                distanceMeters = distanceMeters,
                avgSpeedMps = avgSpeedMps,
                maxSpeedMps = maxSpeedMps,
                stepCount = stepCount,
                gpsPointCount = gpsPointCount,
                durationMs = durationMs
            )
        )
    }

    suspend fun setStatus(trackId: String, status: TrackStatus) {
        val track = trackDao.getById(trackId) ?: return
        trackDao.update(track.copy(status = status))
    }

    suspend fun setUploadStatus(trackId: String, status: UploadStatus) {
        val track = trackDao.getById(trackId) ?: return
        trackDao.update(track.copy(uploadStatus = status))
    }

    suspend fun addPhoto(
        trackId: String,
        type: PhotoType,
        filePath: String,
        timestampMs: Long,
        sample: com.startinsnow.gpstracker.core.model.LocationSample?
    ): PhotoEntity {
        val photo = PhotoEntity(
            photoId = UUID.randomUUID().toString(),
            trackId = trackId,
            type = type,
            filePath = filePath,
            timestampMs = timestampMs,
            latitude = sample?.latitude,
            longitude = sample?.longitude,
            accuracyMeters = sample?.accuracyMeters,
            provider = sample?.provider?.name,
            speedMps = sample?.speedMps,
            altitudeMeters = sample?.altitudeMeters
        )
        photoDao.insert(photo)
        val track = trackDao.getById(trackId)
        if (track != null) {
            trackDao.update(track.copy(photoCount = track.photoCount + 1))
        }
        return photo
    }

    /**
     * Finish Track 時做一次權威性重算，確保就算中途 crash / 統計快照漏更新，最終數字仍然正確。
     *
     * @param activeDurationMs 實際「非暫停」的累積時間（由 Service 提供），
     *   不使用 wall clock 的 end - start，否則會把暫停時間也算進 Duration。
     * @param snapshot* 開始記錄期間的即時統計快照，用於重算結果低於快照時保留較可靠的值。
     */
    suspend fun finalizeTrack(
        trackId: String,
        endTimestampMs: Long,
        stepCount: Long,
        activeDurationMs: Long = 0L,
        snapshotDistanceMeters: Double = 0.0,
        snapshotAvgSpeedMps: Double = 0.0,
        snapshotMaxSpeedMps: Double = 0.0,
        snapshotPointCount: Int = 0
    ) {
        val track = trackDao.getById(trackId) ?: return
        val points = trackPointDao.getAllForTrack(trackId)
        val segments = movementSegmentDao.getAllForTrack(trackId)
        closeOpenMovementSegment(trackId, endTimestampMs)
        val recomputedSegments = movementSegmentDao.getAllForTrack(trackId)
        val stats = StatsCalculator.compute(points, recomputedSegments)
        val lastPoint = points.lastOrNull { it.reliability == "TRUSTED" }
        val trustedPointCount = points.count { it.reliability == "TRUSTED" }

        val modeDistributionJson = Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            JsonObject(stats.modeDistributionPercent.mapKeys { it.key.name }.mapValues { JsonPrimitive(it.value) })
        )

        // 重算結果（以實際落地資料為準）不應小於記錄期間的即時快照，否則使用者會看到距離變少。
        val finalDistance = maxOf(stats.distanceMeters, snapshotDistanceMeters)
        val finalMaxSpeed = maxOf(stats.maxSpeedMps, snapshotMaxSpeedMps)
        val finalAvgSpeed = if (stats.avgSpeedMps > 0.0) stats.avgSpeedMps else snapshotAvgSpeedMps

        trackDao.update(
            track.copy(
                endTimeMs = endTimestampMs,
                status = TrackStatus.FINISHED,
                distanceMeters = finalDistance,
                durationMs = if (activeDurationMs > 0L) activeDurationMs else track.durationMs,
                avgSpeedMps = finalAvgSpeed,
                maxSpeedMps = finalMaxSpeed,
                stepCount = stepCount,
                dominantMode = stats.dominantMode,
                gpsPointCount = maxOf(points.size, snapshotPointCount, trustedPointCount),
                maxAltitudeMeters = stats.maxAltitudeMeters,
                minAltitudeMeters = stats.minAltitudeMeters,
                totalAscentMeters = stats.totalAscentMeters,
                totalDescentMeters = stats.totalDescentMeters,
                endLatitude = lastPoint?.latitude,
                endLongitude = lastPoint?.longitude,
                modeDistributionJson = modeDistributionJson
            )
        )
    }

    /** 放棄一筆未完成的 Track（Crash Recovery 後使用者選擇不續錄時使用）。 */
    suspend fun abandonTrack(trackId: String) {
        val track = trackDao.getById(trackId) ?: return
        closeOpenMovementSegment(trackId, System.currentTimeMillis())
        val stats = StatsCalculator.compute(trackPointDao.getAllForTrack(trackId), movementSegmentDao.getAllForTrack(trackId))
        trackDao.update(
            track.copy(
                status = TrackStatus.FINISHED,
                endTimeMs = System.currentTimeMillis(),
                distanceMeters = maxOf(stats.distanceMeters, track.distanceMeters),
                dominantMode = stats.dominantMode
            )
        )
    }

    suspend fun deleteTrack(trackId: String) {
        val photos = photoDao.getAllForTrack(trackId)
        photos.forEach { photo ->
            runCatching { File(photo.filePath).delete() }
        }
        // 刪除匯出資料夾與打包好的 ZIP，避免留下孤兒檔案。
        val exportDir = File(filesDir, "exports/$trackId")
        if (exportDir.exists()) exportDir.deleteRecursively()
        runCatching { File(filesDir, "exports/$trackId.zip").delete() }
        // 刪除相機原始照片目錄（位於 external files dir，刪 Track 時常被漏掉）。
        photoRootDir?.let { root ->
            val photoDir = File(root, trackId)
            if (photoDir.exists()) photoDir.deleteRecursively()
        }
        // Room 的外鍵 CASCADE 會自動清掉 track_points / photos / movement_segments / gps_outages。
        trackDao.deleteById(trackId)
    }
}
