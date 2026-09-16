package com.startinsnow.gpstracker.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.startinsnow.gpstracker.core.model.MovementMode
import com.startinsnow.gpstracker.core.model.PhotoType
import com.startinsnow.gpstracker.core.model.TrackStatus
import com.startinsnow.gpstracker.core.model.UploadStatus

/** 對應規格「58. Data Architecture」的 Track 資料表。 */
@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val trackId: String,
    val startTimeMs: Long,
    val endTimeMs: Long?,
    val status: TrackStatus,
    val distanceMeters: Double,
    val durationMs: Long,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val stepCount: Long,
    val dominantMode: MovementMode,
    val uploadStatus: UploadStatus,
    val gpsPointCount: Int,
    val photoCount: Int,
    val maxAltitudeMeters: Double?,
    val minAltitudeMeters: Double?,
    val totalAscentMeters: Double,
    val totalDescentMeters: Double,
    val startLatitude: Double?,
    val startLongitude: Double?,
    val endLatitude: Double?,
    val endLongitude: Double?,
    val modeDistributionJson: String
)

@Entity(
    tableName = "track_points",
    indices = [Index("trackId"), Index("timestampMs")],
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["trackId"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val pointId: Long = 0,
    val trackId: String,
    val latitude: Double,
    val longitude: Double,
    val timestampMs: Long,
    val elapsedRealtimeMs: Long,
    val speedMps: Float?,
    val altitudeMeters: Double?,
    val accuracyMeters: Float,
    val provider: String,
    val bearingDegrees: Float?,
    val reliability: String,
    val quality: String,
    val movementMode: MovementMode
)

@Entity(
    tableName = "photos",
    indices = [Index("trackId")],
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["trackId"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class PhotoEntity(
    @PrimaryKey val photoId: String,
    val trackId: String,
    val type: PhotoType,
    val filePath: String,
    val timestampMs: Long,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Float?,
    val provider: String?,
    val speedMps: Float?,
    val altitudeMeters: Double?
)

@Entity(
    tableName = "movement_segments",
    indices = [Index("trackId")],
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["trackId"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MovementSegmentEntity(
    @PrimaryKey(autoGenerate = true) val segmentId: Long = 0,
    val trackId: String,
    val mode: MovementMode,
    val startTimeMs: Long,
    val endTimeMs: Long?
)

@Entity(
    tableName = "gps_outages",
    indices = [Index("trackId")],
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["trackId"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class GpsOutageEntity(
    @PrimaryKey(autoGenerate = true) val outageId: Long = 0,
    val trackId: String,
    val startTimestampMs: Long,
    val endTimestampMs: Long?
)
