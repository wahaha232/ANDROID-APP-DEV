package com.startinsnow.gpstracker.ui.recording

import com.startinsnow.gpstracker.R
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.startinsnow.gpstracker.GpsTrackerApplication
import androidx.lifecycle.viewModelScope
import com.startinsnow.gpstracker.core.model.LocationSample
import com.startinsnow.gpstracker.core.model.MovementMode
import com.startinsnow.gpstracker.core.model.PhotoType
import com.startinsnow.gpstracker.location.LocationTracker
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.startinsnow.gpstracker.photo.TrackPhotoManager
import com.startinsnow.gpstracker.service.TrackingForegroundService
import com.startinsnow.gpstracker.service.TrackingStateHolder

private const val PENDING_DIR = "pending"

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

    /** 開始記錄「之前」拍的照片：此時還沒有 Track，先存起來，Track 建立後再歸入這趟紀錄。 */
    data class PendingPhoto(val file: File, val type: PhotoType, val timestampMs: Long, val sample: LocationSample?)

    private val _pendingPhotos = MutableStateFlow<List<PendingPhoto>>(emptyList())
    val pendingPhotos: StateFlow<List<PendingPhoto>> = _pendingPhotos
    private var waitingToAttach = false

    init {
        // 使用者按下「開始記錄」、服務建立好 Track（出現 trackId）後，把先前拍的照片歸入這個 Track。
        viewModelScope.launch {
            state.map { it.trackId }.filterNotNull().distinctUntilChanged().collect { id ->
                if (waitingToAttach) attachPendingPhotos(id)
            }
        }
    }

    /** [manualMode] 為 null 代表「自動」（依移動速度判斷）。 */
    fun startRecording(manualMode: MovementMode? = null) {
        startedNewTrackFromThisScreen = true
        waitingToAttach = _pendingPhotos.value.isNotEmpty()
        TrackingForegroundService.start(app, manualMode)
    }

    private suspend fun attachPendingPhotos(trackId: String) {
        val photos = _pendingPhotos.value
        if (photos.isEmpty()) {
            waitingToAttach = false
            return
        }
        val destDir = TrackPhotoManager.photoDir(app, trackId).apply { mkdirs() }
        photos.forEach { p ->
            val dest = File(destDir, p.file.name)
            val finalFile = runCatching {
                p.file.copyTo(dest, overwrite = true)
                p.file.delete()
                dest
            }.getOrDefault(p.file)
            runCatching {
                app.trackRepository.addPhoto(trackId, p.type, finalFile.absolutePath, p.timestampMs, p.sample)
            }
            // 正式歸入這趟軌跡了，這時才寫進系統相簿。
            runCatching { photoManager.publishToGallery(finalFile) }
        }
        _pendingPhotos.value = emptyList()
        waitingToAttach = false
        runCatching { TrackPhotoManager.photoDir(app, PENDING_DIR).delete() }
    }

    override fun onCleared() {
        // 拍了照卻沒開始記錄就離開：丟掉暫存的照片檔，不留孤兒檔案。
        if (!waitingToAttach) _pendingPhotos.value.forEach { runCatching { it.file.delete() } }
        super.onCleared()
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
        if (trackId == null) {
            // 尚未開始記錄（準備畫面）：拍到暫存資料夾，第一張當出發點照片，之後的當一般照片。
            return runCatching {
                val sample = runCatching { LocationTracker(app).getLastKnownBestLocation() }.getOrNull()
                val pendingType = if (_pendingPhotos.value.isEmpty()) PhotoType.START else PhotoType.NORMAL
                val file = photoManager.capturePhoto(PENDING_DIR, pendingType, sample, publish = false)
                _pendingPhotos.value = _pendingPhotos.value + PendingPhoto(file, pendingType, System.currentTimeMillis(), sample)
                Unit
            }
        }
        // 記錄中還沒收到第一個點時 lastLocation 是 null，退而求其次用系統最後已知位置，避免照片沒有座標。
        val sample = lastLocation.value
            ?: runCatching { LocationTracker(app).getLastKnownBestLocation() }.getOrNull()
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
