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
    val accuracyLabel: String = "🔴 尚未收到定位",
    val altitudeMeters: Double? = null,
    val isGpsLost: Boolean = true,
    val batteryPercent: Int = 100,
    /** 已寫入資料庫的 GPS 點數（含低可信度點），讓使用者能確認「真的在記錄」。 */
    val pointCount: Int = 0,
    /** 是否已收到過至少一個定位。 */
    val hasFix: Boolean = false,
    /** 是否有可用的步數感測器。 */
    val isStepSensorAvailable: Boolean = true,
    /** Service 是否正在起步（避免重複建立 Track）。 */
    val isStarting: Boolean = false,
    /** 給使用者看的提示 / 錯誤訊息（取代靜默失敗：拍照失敗、定位逾時、Service 啟動失敗…）。 */
    val message: String? = null
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

    /** Crash Recovery：App 啟動時找到的未完成 Track。不自動續錄，交由使用者決定（避免幽靈記錄）。 */
    private val _pendingRecovery = MutableStateFlow<RecoveryCandidate?>(null)
    val pendingRecovery: StateFlow<RecoveryCandidate?> = _pendingRecovery.asStateFlow()

    data class RecoveryCandidate(
        val trackId: String,
        val startTimeMs: Long,
        val pointCount: Int
    )

    fun update(transform: (RecordingUiState) -> RecordingUiState) {
        _state.update(transform)
    }

    fun updateLastLocation(sample: LocationSample?) {
        _lastLocation.value = sample
    }

    /** 顯示一次性提示訊息（UI 顯示後會呼叫 clearMessage 清掉）。 */
    fun postMessage(text: String) {
        _state.update { it.copy(message = text) }
    }

    fun clearMessage() {
        _state.update { if (it.message == null) it else it.copy(message = null) }
    }

    fun setPendingRecovery(candidate: RecoveryCandidate?) {
        _pendingRecovery.value = candidate
    }

    fun reset() {
        _state.value = RecordingUiState()
        _lastLocation.value = null
    }
}
