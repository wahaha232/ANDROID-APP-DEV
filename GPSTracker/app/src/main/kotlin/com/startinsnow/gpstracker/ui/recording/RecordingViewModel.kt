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

    /**
     * 這個 RecordingScreen 是否由使用者主動「開始記錄」而建立新 Track。
     * 續錄（Crash Recovery）回來的 Track 不應再要求拍出發點照片。
     */
    var startedNewTrackFromThisScreen: Boolean = false
        private set

    fun startRecording() {
        startedNewTrackFromThisScreen = true
        TrackingForegroundService.start(app)
    }

    /** 續錄（Crash Recovery）：以既有的 Track ID 重新啟動記錄服務。 */
    fun startResumeRecording(trackId: String) {
        startedNewTrackFromThisScreen = false
        TrackingForegroundService.startResume(app, trackId)
    }

    fun pauseRecording() = TrackingForegroundService.pause(app)
    fun resumeRecording() = TrackingForegroundService.resume(app)
    fun stopRecording() = TrackingForegroundService.stop(app)

    /** 拍照；失敗時回傳可顯示給使用者的訊息，而不是靜默失敗。 */
    suspend fun capturePhoto(type: PhotoType): Result<Unit> {
        val trackId = state.value.trackId
            ?: return Result.failure(IllegalStateException("目前沒有進行中的記錄，無法拍照"))
        val sample = lastLocation.value
        return runCatching {
            val file = photoManager.capturePhoto(trackId, type, sample)
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
