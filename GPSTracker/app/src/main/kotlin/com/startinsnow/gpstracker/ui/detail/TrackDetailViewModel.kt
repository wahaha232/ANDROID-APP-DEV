package com.startinsnow.gpstracker.ui.detail

import com.startinsnow.gpstracker.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.startinsnow.gpstracker.GpsTrackerApplication
import com.startinsnow.gpstracker.data.db.PhotoEntity
import com.startinsnow.gpstracker.data.db.GpsOutageEntity
import com.startinsnow.gpstracker.data.db.TrackEntity
import com.startinsnow.gpstracker.data.db.TrackPointEntity
import com.startinsnow.gpstracker.data.prefs.StepSource
import com.startinsnow.gpstracker.export.ExportTrackData
import com.startinsnow.gpstracker.playback.PlaybackFrame
import com.startinsnow.gpstracker.playback.PlaybackPhotoMarker
import com.startinsnow.gpstracker.playback.PlaybackPoint
import com.startinsnow.gpstracker.playback.TrackPlaybackEngine
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.startinsnow.gpstracker.sync.StepResolver
import com.startinsnow.gpstracker.sync.StepSourceTag
import com.startinsnow.gpstracker.sync.WebDashboardSync
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlaybackUiState(
    val isPlaying: Boolean = false,
    val elapsedMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val speedMultiplier: Double = 5.0,
    val frame: PlaybackFrame? = null
)

class TrackDetailViewModel(
    private val app: GpsTrackerApplication,
    private val trackId: String
) : ViewModel() {

    val track: StateFlow<TrackEntity?> = app.trackRepository.observeTrack(trackId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val points: StateFlow<List<TrackPointEntity>> = app.trackRepository.observePoints(trackId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val photos: StateFlow<List<PhotoEntity>> = app.trackRepository.observePhotos(trackId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** GPS 中斷區間（給地圖畫出 Signal Lost 標記用）。 */
    private val _outages = MutableStateFlow<List<GpsOutageEntity>>(emptyList())
    val outages: StateFlow<List<GpsOutageEntity>> = _outages

    private var playbackEngine: TrackPlaybackEngine = TrackPlaybackEngine(emptyList())

    private val _playback = MutableStateFlow(PlaybackUiState())
    val playback: StateFlow<PlaybackUiState> = _playback

    private var tickerJob: Job? = null

    init {
        viewModelScope.launch {
            runCatching { _outages.value = app.trackRepository.getOutages(trackId) }
        }
        viewModelScope.launch {
            combine(points, photos) { pts, phs -> pts to phs }.collect { (pts, phs) ->
                val playbackPoints = pts.sortedBy { it.timestampMs }.map {
                    PlaybackPoint(
                        timestampMs = it.timestampMs,
                        latitude = it.latitude,
                        longitude = it.longitude,
                        speedMps = it.speedMps?.toDouble() ?: 0.0,
                        altitudeMeters = it.altitudeMeters,
                        mode = it.movementMode,
                        trusted = it.reliability == "TRUSTED"
                    )
                }
                val playbackPhotos = phs.map { PlaybackPhotoMarker(it.photoId, it.timestampMs, it.type, it.latitude, it.longitude) }
                playbackEngine = TrackPlaybackEngine(playbackPoints, playbackPhotos)
                _playback.value = _playback.value.copy(
                    totalDurationMs = playbackEngine.totalDurationMs,
                    frame = playbackEngine.frameAt(_playback.value.elapsedMs)
                )
            }
        }
    }

    fun play() {
        if (playbackEngine.isEmpty()) return
        // 播到結尾後再按「播放」要從頭開始，否則會立刻又判定結束、看起來沒反應。
        if (_playback.value.elapsedMs >= playbackEngine.totalDurationMs) seekTo(0L)
        _playback.value = _playback.value.copy(isPlaying = true)
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            var lastTick = System.currentTimeMillis()
            while (isActive && _playback.value.isPlaying) {
                delay(100)
                val now = System.currentTimeMillis()
                val deltaMs = ((now - lastTick) * _playback.value.speedMultiplier).toLong()
                lastTick = now
                val newElapsed = (_playback.value.elapsedMs + deltaMs).coerceAtMost(playbackEngine.totalDurationMs)
                val frame = playbackEngine.frameAt(newElapsed)
                _playback.value = _playback.value.copy(elapsedMs = newElapsed, frame = frame, isPlaying = !frame.finished)
                if (frame.finished) break
            }
        }
    }

    /**
     * 手動重新同步步數：依設定的來源重讀這趟軌跡時間範圍的 Health Connect 步數，讀到就更新並回傳說明文字。
     * Zepp Life 需先讓手環同步到手機並寫入 Health Connect，再按此鈕。
     */
    suspend fun resyncSteps(): String {
        val t = app.trackRepository.getTrack(trackId) ?: return app.getString(R.string.detail_vm_001)
        val endMs = t.endTimeMs ?: return app.getString(R.string.detail_vm_002)
        val source = app.settingsRepository.settings.first().stepSource
        if (source == StepSource.DEVICE_SENSOR) {
            return app.getString(R.string.detail_vm_003)
        }
        return withContext(Dispatchers.IO) {
            StepResolver(app).readHealthConnect(source, t.startTimeMs, endMs).fold(
                onSuccess = { steps ->
                    if (steps > 0) {
                        val tag = StepSourceTag.forSource(source)
                        app.trackRepository.updateSteps(trackId, steps, tag)
                        val web = WebDashboardSync.uploadIfConfigured(app, trackId)
                        val webNote = when {
                            web.isFailure -> app.getString(R.string.detail_vm_004, web.exceptionOrNull()?.message)
                            web.getOrNull() == WebDashboardSync.Outcome.UPLOADED -> app.getString(R.string.detail_vm_005)
                            else -> ""
                        }
                        app.getString(R.string.detail_vm_006, steps, app.getString(StepSourceTag.labelRes(tag)), webNote)
                    } else {
                        app.getString(R.string.detail_vm_007)
                    }
                },
                onFailure = { app.getString(R.string.detail_vm_008, it.message ?: it::class.java.simpleName) }
            )
        }
    }

    fun pause() {
        tickerJob?.cancel()
        _playback.value = _playback.value.copy(isPlaying = false)
    }

    fun stop() {
        tickerJob?.cancel()
        val frame = playbackEngine.frameAt(0L)
        _playback.value = _playback.value.copy(isPlaying = false, elapsedMs = 0L, frame = frame)
    }

    fun seekTo(elapsedMs: Long) {
        val frame = playbackEngine.frameAt(elapsedMs)
        _playback.value = _playback.value.copy(elapsedMs = elapsedMs, frame = frame)
    }

    fun setSpeedMultiplier(multiplier: Double) {
        _playback.value = _playback.value.copy(speedMultiplier = multiplier)
    }

    /** 產生匯出 ZIP。HTML/GPX/JSON/CSV 產生、照片複製與壓縮都是重 IO，一律在 IO 執行緒做，避免卡住主畫面（ANR）。 */
    suspend fun buildExportZip(): File? = withContext(Dispatchers.IO) {
        val data = buildExportData() ?: return@withContext null
        val exportDir = com.startinsnow.gpstracker.export.TrackZipExporter.buildExportDirectory(app.filesDir, data)
        val zipFile = File(app.filesDir, "exports/${trackId}.zip")
        com.startinsnow.gpstracker.export.TrackZipExporter.zipDirectory(exportDir, zipFile)
        zipFile
    }

    /**
     * 匯出／上傳用資料：一律從資料庫現查。不可用 points.value——那是 WhileSubscribed 的 StateFlow，
     * 剛進畫面、第一筆資料還沒送達時是 emptyList()，會匯出或上傳「空軌跡」。
     */
    suspend fun buildExportData(): ExportTrackData? {
        val trackEntity = app.trackRepository.getTrack(trackId) ?: return null
        return ExportTrackData(
            track = trackEntity,
            points = app.trackRepository.getPoints(trackId),
            photos = app.trackRepository.getPhotos(trackId),
            segments = app.trackRepository.getSegments(trackId),
            outages = app.trackRepository.getOutages(trackId)
        )
    }

    fun deleteTrack() {
        viewModelScope.launch { app.trackRepository.deleteTrack(trackId) }
    }

    companion object {
        fun factory(app: GpsTrackerApplication, trackId: String) = viewModelFactory {
            initializer { TrackDetailViewModel(app, trackId) }
        }
    }
}
