package com.startinsnow.gpstracker.data.repository

import com.startinsnow.gpstracker.core.geo.GeoMath
import com.startinsnow.gpstracker.core.model.MovementMode
import com.startinsnow.gpstracker.data.db.MovementSegmentEntity
import com.startinsnow.gpstracker.data.db.TrackPointEntity

/**
 * 從已儲存的 TrackPoint 重新計算 Track 統計資料（距離 / 速度 / 海拔 / 模式分布）。
 * 純邏輯，不依賴 Room/Android，供 Repository 在 Finish Track 或 Crash Recovery 後重新校正資料，
 * 也方便單獨用 Unit Test 驗證計算正確性（規格 80）。
 *
 * 只有 reliability == "TRUSTED" 的點會被納入距離/速度計算，避免漂移/低精度點污染統計數字（規格 8）。
 */
object StatsCalculator {

    data class TrackStats(
        val distanceMeters: Double,
        val avgSpeedMps: Double,
        val maxSpeedMps: Double,
        val maxAltitudeMeters: Double?,
        val minAltitudeMeters: Double?,
        val totalAscentMeters: Double,
        val totalDescentMeters: Double,
        val dominantMode: MovementMode,
        val modeDistributionPercent: Map<MovementMode, Double>
    )

    fun compute(points: List<TrackPointEntity>, segments: List<MovementSegmentEntity>): TrackStats {
        val trusted = points.filter { it.reliability == "TRUSTED" }.sortedBy { it.timestampMs }

        var distance = 0.0
        var maxSpeed = 0.0
        val speedSamples = mutableListOf<Double>()

        for (i in 1 until trusted.size) {
            val prev = trusted[i - 1]
            val curr = trusted[i]
            val segmentDistance = GeoMath.distanceMeters(prev.latitude, prev.longitude, curr.latitude, curr.longitude)
            distance += segmentDistance

            val deltaSeconds = (curr.timestampMs - prev.timestampMs) / 1000.0
            val impliedSpeed = if (deltaSeconds > 0) segmentDistance / deltaSeconds else 0.0
            val reportedSpeed = curr.speedMps?.toDouble()
            val speed = reportedSpeed ?: impliedSpeed
            speedSamples += speed
            if (speed > maxSpeed) maxSpeed = speed
        }

        val avgSpeed = if (speedSamples.isNotEmpty()) speedSamples.average() else 0.0

        // 海拔：加入輕量平滑，避免 GPS altitude noise 讓爬升/下降被誇大（規格 40）。
        val altitudes = trusted.mapNotNull { it.altitudeMeters }
        val smoothedAltitudes = smoothAltitude(altitudes)
        var ascent = 0.0
        var descent = 0.0
        for (i in 1 until smoothedAltitudes.size) {
            val diff = smoothedAltitudes[i] - smoothedAltitudes[i - 1]
            if (diff > ALTITUDE_NOISE_THRESHOLD_M) ascent += diff
            else if (diff < -ALTITUDE_NOISE_THRESHOLD_M) descent += -diff
        }

        val modeDurations = mutableMapOf<MovementMode, Long>()
        for (segment in segments) {
            val end = segment.endTimeMs ?: segment.startTimeMs
            val duration = (end - segment.startTimeMs).coerceAtLeast(0L)
            modeDurations[segment.mode] = (modeDurations[segment.mode] ?: 0L) + duration
        }
        val totalDuration = modeDurations.values.sum().takeIf { it > 0 }
        val modeDistribution = if (totalDuration != null) {
            modeDurations.mapValues { (_, duration) -> duration * 100.0 / totalDuration }
        } else emptyMap()
        val dominantMode = modeDurations.maxByOrNull { it.value }?.key ?: MovementMode.UNKNOWN

        return TrackStats(
            distanceMeters = distance,
            avgSpeedMps = avgSpeed,
            maxSpeedMps = maxSpeed,
            maxAltitudeMeters = altitudes.maxOrNull(),
            minAltitudeMeters = altitudes.minOrNull(),
            totalAscentMeters = ascent,
            totalDescentMeters = descent,
            dominantMode = dominantMode,
            modeDistributionPercent = modeDistribution
        )
    }

    private fun smoothAltitude(values: List<Double>): List<Double> {
        if (values.size < 3) return values
        val result = ArrayList<Double>(values.size)
        result += values.first()
        for (i in 1 until values.size - 1) {
            result += (values[i - 1] + values[i] + values[i + 1]) / 3.0
        }
        result += values.last()
        return result
    }

    private const val ALTITUDE_NOISE_THRESHOLD_M = 1.5
}
