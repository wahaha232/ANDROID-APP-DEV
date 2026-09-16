package com.startinsnow.gpstracker.ui.recording

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.startinsnow.gpstracker.GpsTrackerApplication
import com.startinsnow.gpstracker.core.model.PhotoType
import com.startinsnow.gpstracker.photo.TrackPhotoManager
import com.startinsnow.gpstracker.service.TrackingForegroundService
import com.startinsnow.gpstracker.service.TrackingStateHolder

class RecordingViewModel(application: Application) : AndroidViewModel(application) {
    private val app get() = getApplication<GpsTrackerApplication>()

    val state = TrackingStateHolder.state
    val lastLocation = TrackingStateHolder.lastLocation
    val photoManager = TrackPhotoManager(app)

    fun startRecording() = TrackingForegroundService.start(app)
    fun pauseRecording() = TrackingForegroundService.pause(app)
    fun resumeRecording() = TrackingForegroundService.resume(app)
    fun stopRecording() = TrackingForegroundService.stop(app)

    suspend fun capturePhoto(type: PhotoType): Result<Unit> {
        val trackId = state.value.trackId ?: return Result.failure(IllegalStateException("目前沒有進行中的 Track"))
        return runCatching {
            val file = photoManager.capturePhoto(trackId, type)
            val sample = lastLocation.value
            app.trackRepository.addPhoto(
                trackId = trackId,
                type = type,
                filePath = file.absolutePath,
                timestampMs = System.currentTimeMillis(),
                sample = sample
            )
            Unit
        }
    }
}
