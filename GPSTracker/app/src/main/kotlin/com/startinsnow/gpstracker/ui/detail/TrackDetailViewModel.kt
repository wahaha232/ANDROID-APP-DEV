package com.startinsnow.gpstracker.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.startinsnow.gpstracker.GpsTrackerApplication
import com.startinsnow.gpstracker.data.db.PhotoEntity
import com.startinsnow.gpstracker.data.db.TrackEntity
import com.startinsnow.gpstracker.data.db.TrackPointEntity
import com.startinsnow.gpstracker.export.ExportTrackData
import com.startinsnow.gpstracker.playback.PlaybackFrame
import com.startinsnow.gpstracker.playback.PlaybackPhotoMarker
import com.startinsnow.gpstracker.playback.PlaybackPoint
import com.startinsnow.gpstracker.playback.TrackPlaybackEngine
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.io.File
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

    private var playbackEngine: TrackPlaybackEngine = TrackPlaybackEngine(emptyList())

    private val _playback = MutableStateFlow(PlaybackUiState())
    val playback: StateFlow<PlaybackUiState> = _playback

    private var tickerJob: Job? = null

    init {
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

    suspend fun buildExportZip(): File? {
        val trackEntity = track.value ?: return null
        val data = ExportTrackData(
            track = trackEntity,
            points = points.value,
            photos = photos.value,
            segments = app.trackRepository.getSegments(trackId),
            outages = app.trackRepository.getOutages(trackId)
        )
        val exportDir = com.startinsnow.gpstracker.export.TrackZipExporter.buildExportDirectory(app.filesDir, data)
        val zipFile = File(app.filesDir, "exports/${trackId}.zip")
        com.startinsnow.gpstracker.export.TrackZipExporter.zipDirectory(exportDir, zipFile)
        return zipFile
    }

    suspend fun buildExportData(): ExportTrackData? {
        val trackEntity = track.value ?: return null
        return ExportTrackData(
            track = trackEntity,
            points = points.value,
            photos = photos.value,
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
