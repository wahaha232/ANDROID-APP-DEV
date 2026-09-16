package com.startinsnow.gpstracker.core.model

/**
 * 平台無關的定位樣本。所有 GPS / Movement / Drift 演算法都只依賴這個資料類別，
 * 不直接依賴 android.location.Location，因此核心邏輯可以在純 JVM Unit Test 中驗證。
 */
data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    /** epoch millis，來自 System.currentTimeMillis() 對齊後的時間戳。 */
    val timestampMs: Long,
    /** SystemClock.elapsedRealtime()，用於避免系統時間被使用者調整影響時間差計算。 */
    val elapsedRealtimeMs: Long,
    val speedMps: Float?,
    val speedAccuracyMps: Float?,
    val altitudeMeters: Double?,
    val altitudeAccuracyMeters: Float?,
    val bearingDegrees: Float?,
    val bearingAccuracyDegrees: Float?,
    val accuracyMeters: Float,
    val provider: LocationProvider,
    val trackId: String
)

enum class LocationProvider {
    GPS,
    NETWORK,
    PASSIVE,
    FUSED,
    UNKNOWN
}

enum class LocationQuality {
    GOOD,       // 🟢
    NORMAL,     // 🟡
    WEAK,       // 🟠
    LOST;       // 🔴

    val emoji: String
        get() = when (this) {
            GOOD -> "🟢"
            NORMAL -> "🟡"
            WEAK -> "🟠"
            LOST -> "🔴"
        }
}

/** GPS 點被漂移過濾器接受後的可信度分類，供 Distance/Speed/Movement 計算決定是否採用。 */
enum class PointReliability {
    TRUSTED,        // 正常使用
    LOW_CONFIDENCE, // 保留顯示，但不計入 distance/speed/movement
    REJECTED        // 明顯異常，完全捨棄（仍保存於資料庫供除錯）
}

data class FilteredLocation(
    val sample: LocationSample,
    val reliability: PointReliability,
    val quality: LocationQuality,
    val reason: String? = null
)

enum class MovementMode {
    WALKING,
    BICYCLE,
    MOTORCYCLE,
    PUBLIC_TRANSPORT,
    CAR,
    AIRPLANE,
    STATIONARY,
    UNKNOWN;

    val emoji: String
        get() = when (this) {
            WALKING -> "🚶"
            BICYCLE -> "🚲"
            MOTORCYCLE -> "🏍️"
            PUBLIC_TRANSPORT -> "🚌"
            CAR -> "🚗"
            AIRPLANE -> "✈️"
            STATIONARY -> "🛑"
            UNKNOWN -> "❓"
        }
}

enum class TrackStatus {
    RECORDING,
    PAUSED,
    FINISHED
}

enum class PhotoType {
    START,
    NORMAL,
    END
}

enum class UploadStatus {
    NOT_UPLOADED,
    UPLOADING,
    UPLOADED,
    FAILED
}

enum class BatteryMode {
    NORMAL,
    POWER_SAVING,
    ULTRA_POWER_SAVING
}

data class GpsOutageWindow(
    val trackId: String,
    val startTimestampMs: Long,
    val endTimestampMs: Long? = null
) {
    val durationMs: Long?
        get() = endTimestampMs?.let { it - startTimestampMs }
}
