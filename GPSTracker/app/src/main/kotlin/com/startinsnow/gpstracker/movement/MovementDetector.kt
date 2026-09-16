package com.startinsnow.gpstracker.movement

import com.startinsnow.gpstracker.core.geo.GeoMath
import com.startinsnow.gpstracker.core.model.MovementMode
import com.startinsnow.gpstracker.core.model.PointReliability

/** 一個進入 Movement 判斷窗口的樣本。已經過 [com.startinsnow.gpstracker.location.GpsDriftFilter] 篩選。 */
data class MovementSample(
    val timestampMs: Long,
    val speedMps: Double,
    val accuracyMeters: Float,
    val altitudeMeters: Double?,
    val reliability: PointReliability
)

data class MovementResult(
    val mode: MovementMode,
    /** 0.0..1.0，樣本量與窗口穩定度換算出的信心值。 */
    val confidence: Double
)

/**
 * 交通模式偵測引擎，對應規格「13~16、74. Movement Engine」。
 *
 * 設計重點：
 *  - 使用一段時間窗口（預設 30 秒）的平均/變異速度，而不是單點瞬時速度。
 *  - Hysteresis：新模式必須連續穩定判斷 [minSamplesForChange] 次才會真正切換，避免來回跳動。
 *  - 低可信度（LOW_CONFIDENCE / REJECTED）的點完全不進入窗口，因此室內低精度定位不會誤判為高速交通。
 *  - 飛機判斷需要「窗口內平均速度持續偏高一段時間」，而非單點速度 > 門檻。
 */
class MovementDetector(
    private val windowMs: Long = 30_000L,
    private val minSamplesForChange: Int = 3,
    /** 兩次 Mode 切換之間的最短間隔，避免 Mode 快速跳動（例如 Bicycle→Car→Bicycle）。 */
    private val minDwellMs: Long = MIN_DWELL_MS
) {
    private val window = ArrayDeque<MovementSample>()
    private var currentMode = MovementMode.UNKNOWN
    private var candidateMode = MovementMode.UNKNOWN
    private var candidateStreak = 0
    private var lastModeChangeTimestampMs: Long? = null

    fun addSample(sample: MovementSample): MovementResult {
        if (sample.reliability != PointReliability.TRUSTED) {
            // 規格 15：低精度/漂移點不得改變 Movement Mode，保留上一個可信結果。
            return MovementResult(currentMode, confidenceFor(window.size))
        }

        window.addLast(sample)
        while (window.isNotEmpty() && sample.timestampMs - window.first().timestampMs > windowMs) {
            window.removeFirst()
        }

        val classification = classifyWindow()
        applyHysteresis(classification, sample.timestampMs)

        return MovementResult(currentMode, confidenceFor(window.size))
    }

    fun currentMode(): MovementMode = currentMode

    fun reset() {
        window.clear()
        currentMode = MovementMode.UNKNOWN
        candidateMode = MovementMode.UNKNOWN
        candidateStreak = 0
        lastModeChangeTimestampMs = null
    }

    private fun applyHysteresis(classification: MovementMode, timestampMs: Long) {
        when {
            classification == currentMode -> {
                candidateMode = currentMode
                candidateStreak = 0
            }
            classification == candidateMode -> {
                candidateStreak++
                if (candidateStreak >= minSamplesForChange && dwellSatisfied(timestampMs)) {
                    currentMode = candidateMode
                    candidateStreak = 0
                    lastModeChangeTimestampMs = timestampMs
                }
            }
            else -> {
                candidateMode = classification
                candidateStreak = 1
            }
        }
    }

    /** Hysteresis 的第二層保護：Mode 一旦確定，必須維持 [minDwellMs] 才能再切換。 */
    private fun dwellSatisfied(timestampMs: Long): Boolean {
        val last = lastModeChangeTimestampMs ?: return true
        return (timestampMs - last) >= minDwellMs
    }

    private fun confidenceFor(sampleCount: Int): Double =
        (sampleCount.toDouble() / MIN_SAMPLES_FOR_FULL_CONFIDENCE).coerceIn(0.0, 1.0)

    private fun classifyWindow(): MovementMode {
        if (window.isEmpty()) return MovementMode.UNKNOWN

        val speedsMps = window.map { it.speedMps }
        val avgKmh = GeoMath.msToKmh(speedsMps.average())
        val maxKmh = GeoMath.msToKmh(speedsMps.max())
        val minKmh = GeoMath.msToKmh(speedsMps.min())
        val variationKmh = maxKmh - minKmh
        val stopRatio = window.count { it.speedMps < STOP_SPEED_MPS }.toDouble() / window.size
        val windowDurationSeconds = (window.last().timestampMs - window.first().timestampMs) / 1000.0
        val avgAltitude = window.mapNotNull { it.altitudeMeters }
            .let { if (it.isEmpty()) null else it.average() }

        return when {
            avgKmh < STATIONARY_MAX_KMH -> MovementMode.STATIONARY

            avgKmh >= AIRPLANE_MIN_KMH -> {
                // 需要窗口涵蓋足夠時間長度的持續高速（而非單點瞬時速度）＋足夠樣本數，避免 GPS 尖峰誤判。
                val sustained = windowDurationSeconds >= MIN_AIRPLANE_WINDOW_SECONDS &&
                    window.size >= MIN_AIRPLANE_SAMPLES
                val altitudeSupportsFlight = avgAltitude == null || avgAltitude > MIN_AIRPLANE_ALTITUDE_M ||
                    avgKmh >= UNAMBIGUOUS_AIRPLANE_KMH
                if (sustained && altitudeSupportsFlight) MovementMode.AIRPLANE else fallbackGroundMode(avgKmh, stopRatio, variationKmh)
            }

            avgKmh < WALKING_MAX_KMH -> MovementMode.WALKING

            avgKmh < BICYCLE_MAX_KMH -> MovementMode.BICYCLE

            else -> fallbackGroundMode(avgKmh, stopRatio, variationKmh)
        }
    }

    /** 機車 / 汽車 / 大眾運輸僅靠 GPS 速度難以完全區分，採用停等比例與速度變異度做近似判斷。 */
    private fun fallbackGroundMode(avgKmh: Double, stopRatio: Double, variationKmh: Double): MovementMode = when {
        stopRatio > PUBLIC_TRANSPORT_STOP_RATIO -> MovementMode.PUBLIC_TRANSPORT
        variationKmh > MOTORCYCLE_VARIATION_KMH -> MovementMode.MOTORCYCLE
        else -> MovementMode.CAR
    }

    companion object {
        const val STATIONARY_MAX_KMH = 1.5
        const val WALKING_MAX_KMH = 7.0
        const val BICYCLE_MAX_KMH = 25.0
        const val AIRPLANE_MIN_KMH = 150.0
        const val UNAMBIGUOUS_AIRPLANE_KMH = 300.0

        const val MIN_AIRPLANE_WINDOW_SECONDS = 15.0
        const val MIN_AIRPLANE_ALTITUDE_M = 1000.0

        /** 判定飛機至少需要在窗口內累積的樣本數，避免 2~3 個尖峰點就誤判。 */
        const val MIN_AIRPLANE_SAMPLES = 4

        /** Mode 切換後的最短停留時間（毫秒），避免 Mode 快速跳動。 */
        const val MIN_DWELL_MS = 5_000L

        const val STOP_SPEED_MPS = 0.6
        const val PUBLIC_TRANSPORT_STOP_RATIO = 0.2
        const val MOTORCYCLE_VARIATION_KMH = 40.0

        const val MIN_SAMPLES_FOR_FULL_CONFIDENCE = 8
    }
}
