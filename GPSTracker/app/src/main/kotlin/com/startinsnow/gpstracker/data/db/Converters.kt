package com.startinsnow.gpstracker.data.db

import androidx.room.TypeConverter
import com.startinsnow.gpstracker.core.model.MovementMode
import com.startinsnow.gpstracker.core.model.PhotoType
import com.startinsnow.gpstracker.core.model.TrackStatus
import com.startinsnow.gpstracker.core.model.UploadStatus

class Converters {
    @TypeConverter
    fun movementModeToString(mode: MovementMode): String = mode.name

    @TypeConverter
    fun stringToMovementMode(value: String): MovementMode =
        runCatching { MovementMode.valueOf(value) }.getOrDefault(MovementMode.UNKNOWN)

    @TypeConverter
    fun trackStatusToString(status: TrackStatus): String = status.name

    @TypeConverter
    fun stringToTrackStatus(value: String): TrackStatus =
        runCatching { TrackStatus.valueOf(value) }.getOrDefault(TrackStatus.FINISHED)

    @TypeConverter
    fun uploadStatusToString(status: UploadStatus): String = status.name

    @TypeConverter
    fun stringToUploadStatus(value: String): UploadStatus =
        runCatching { UploadStatus.valueOf(value) }.getOrDefault(UploadStatus.NOT_UPLOADED)

    @TypeConverter
    fun photoTypeToString(type: PhotoType): String = type.name

    @TypeConverter
    fun stringToPhotoType(value: String): PhotoType =
        runCatching { PhotoType.valueOf(value) }.getOrDefault(PhotoType.NORMAL)
}
