package com.startinsnow.gpstracker.service

import com.startinsnow.gpstracker.core.model.LocationQuality
import com.startinsnow.gpstracker.core.model.LocationSample
import com.startinsnow.gpstracker.core.model.MovementMode
import com.startinsnow.gpstracker.core.model.TrackStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class RecordingUiState(
    val status: TrackStatus = TrackStatus.FINISHED,
    val trackId: String? = null,
    val isAutoPaused: Boolean = false,
    val currentSpeedKmh: Double = 0.0,
    val distanceMeters: Double = 0.0,
    val durationMs: Long = 0L,
    val stepCount: Long = 0L,
    val movementMode: MovementMode = MovementMode.UNKNOWN,
    val gpsQuality: LocationQuality = LocationQuality.LOST,
    val accuracyMeters: Float? = null,
    val accuracyLabel: String = "🔴 GPS 遺失",
    val altitudeMeters: Double? = null,
    val isGpsLost: Boolean = true,
    val batteryPercent: Int = 100
)

/**
 * Service（寫入）與 UI（讀取）之間共用的即時狀態。一個 App 同時只會有一個進行中的 Track，
 * 因此用單例 StateFlow 承接即可，避免在 Service 與 ViewModel 之間額外綁定 Binder 介面的複雜度。
 */
object TrackingStateHolder {
    private val _state = MutableStateFlow(RecordingUiState())
    val state: StateFlow<RecordingUiState> = _state.asStateFlow()

    private val _lastLocation = MutableStateFlow<LocationSample?>(null)
    val lastLocation: StateFlow<LocationSample?> = _lastLocation.asStateFlow()

    fun update(transform: (RecordingUiState) -> RecordingUiState) {
        _state.update(transform)
    }

    fun updateLastLocation(sample: LocationSample?) {
        _lastLocation.value = sample
    }

    fun reset() {
        _state.value = RecordingUiState()
        _lastLocation.value = null
    }
}
