package com.startinsnow.gpstracker.location

import com.startinsnow.gpstracker.core.model.BatteryMode
import com.startinsnow.gpstracker.core.model.LocationQuality
import com.startinsnow.gpstracker.core.model.MovementMode

/**
 * 動態 GPS 取樣策略：Time + Distance + Motion，對應規格「7. GPS 智慧取樣」與「54/55. 電量策略」。
 * 純邏輯、無 Android 依賴，方便 Unit Test 驗證各種組合。
 */
data class SamplingPlan(
    val minIntervalMs: Long,
    val minDistanceMeters: Double
)

object AdaptiveSamplingPolicy {

    /**
     * @param stationaryDurationMs 連續判定為靜止的時間，越久取樣間隔可以拉得更長（省電）。
     */
    fun plan(
        mode: MovementMode,
        quality: LocationQuality,
        batteryMode: BatteryMode,
        stationaryDurationMs: Long = 0L
    ): SamplingPlan {
        val base = baseIntervalMs(mode, stationaryDurationMs)
        val baseDistance = baseMinDistanceMeters(mode)

        val batteryMultiplier = when (batteryMode) {
            BatteryMode.NORMAL -> 1.0
            BatteryMode.POWER_SAVING -> 1.5
            BatteryMode.ULTRA_POWER_SAVING -> 2.5
        }

        val qualityFloorMs = when (quality) {
            LocationQuality.GOOD -> 0L
            LocationQuality.NORMAL -> 0L
            LocationQuality.WEAK -> 10_000L
            LocationQuality.LOST -> 15_000L
        }

        val interval = (base * batteryMultiplier).toLong().coerceAtLeast(qualityFloorMs)
            .coerceAtMost(MAX_INTERVAL_MS)

        return SamplingPlan(minIntervalMs = interval, minDistanceMeters = baseDistance)
    }

    private fun baseIntervalMs(mode: MovementMode, stationaryDurationMs: Long): Long = when (mode) {
        MovementMode.STATIONARY -> {
            // 靜止越久，取樣間隔在 30~60 秒之間逐步拉長。
            val extra = (stationaryDurationMs / 60_000L) * 5_000L
            (30_000L + extra).coerceAtMost(60_000L)
        }
        MovementMode.WALKING -> 5_000L
        MovementMode.BICYCLE -> 3_000L
        MovementMode.MOTORCYCLE -> 2_500L
        MovementMode.CAR -> 2_500L
        MovementMode.PUBLIC_TRANSPORT -> 4_000L
        MovementMode.AIRPLANE -> 7_000L
        MovementMode.UNKNOWN -> 10_000L
    }

    private fun baseMinDistanceMeters(mode: MovementMode): Double = when (mode) {
        MovementMode.STATIONARY -> 0.0
        MovementMode.WALKING -> 3.0
        MovementMode.BICYCLE -> 7.0
        MovementMode.MOTORCYCLE -> 8.0
        MovementMode.CAR -> 10.0
        MovementMode.PUBLIC_TRANSPORT -> 8.0
        MovementMode.AIRPLANE -> 50.0
        MovementMode.UNKNOWN -> 5.0
    }

    const val MAX_INTERVAL_MS = 60_000L
}
