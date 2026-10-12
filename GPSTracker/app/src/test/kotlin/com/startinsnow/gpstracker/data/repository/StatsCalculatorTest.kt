package com.startinsnow.gpstracker.data.repository

import com.startinsnow.gpstracker.core.model.MovementMode
import com.startinsnow.gpstracker.data.db.MovementSegmentEntity
import com.startinsnow.gpstracker.data.db.TrackPointEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsCalculatorTest {

    private fun point(
        lat: Double,
        lon: Double,
        timeMs: Long,
        reliability: String = "TRUSTED",
        speedMps: Float? = null,
        altitude: Double? = null
    ) = TrackPointEntity(
        trackId = "t1",
        latitude = lat,
        longitude = lon,
        timestampMs = timeMs,
        elapsedRealtimeMs = timeMs,
        speedMps = speedMps,
        altitudeMeters = altitude,
        accuracyMeters = 8f,
        provider = "GPS",
        bearingDegrees = null,
        reliability = reliability,
        quality = "GOOD",
        movementMode = MovementMode.WALKING
    )

    @Test
    fun `distance accumulates only across trusted points`() {
        val points = listOf(
            point(25.0000, 121.0000, 0L),
            point(25.0010, 121.0000, 10_000L), // trusted, ~111m
            point(25.5000, 121.5000, 15_000L, reliability = "REJECTED"), // must be ignored
            point(25.0020, 121.0000, 20_000L) // trusted, another ~111m
        )
        val stats = StatsCalculator.compute(points, emptyList())
        assertEquals(222.0, stats.distanceMeters, 5.0)
    }

    @Test
    fun `average and max speed use reported speed when available`() {
        val points = listOf(
            point(25.0000, 121.0000, 0L, speedMps = 2f),
            point(25.0010, 121.0000, 10_000L, speedMps = 4f),
            point(25.0020, 121.0000, 20_000L, speedMps = 10f)
        )
        val stats = StatsCalculator.compute(points, emptyList())
        assertEquals(10.0, stats.maxSpeedMps, 0.001)
    }

    @Test
    fun `altitude ascent and descent are smoothed and noise below threshold is ignored`() {
        val points = listOf(
            point(25.0, 121.0, 0L, altitude = 100.0),
            point(25.0001, 121.0, 5_000L, altitude = 100.5), // tiny noise
            point(25.0002, 121.0, 10_000L, altitude = 120.0),
            point(25.0003, 121.0, 15_000L, altitude = 110.0)
        )
        val stats = StatsCalculator.compute(points, emptyList())
        assertEquals(120.0, stats.maxAltitudeMeters!!, 0.001)
        assertEquals(100.0, stats.minAltitudeMeters!!, 0.001)
    }

    @Test
    fun `dominant mode and distribution derive from segment durations`() {
        val segments = listOf(
            MovementSegmentEntity(trackId = "t1", mode = MovementMode.BICYCLE, startTimeMs = 0L, endTimeMs = 8_000L),
            MovementSegmentEntity(trackId = "t1", mode = MovementMode.WALKING, startTimeMs = 8_000L, endTimeMs = 10_000L)
        )
        val stats = StatsCalculator.compute(emptyList(), segments)
        assertEquals(MovementMode.BICYCLE, stats.dominantMode)
        assertEquals(80.0, stats.modeDistributionPercent[MovementMode.BICYCLE]!!, 0.001)
        assertEquals(20.0, stats.modeDistributionPercent[MovementMode.WALKING]!!, 0.001)
    }

    @Test
    fun `empty input produces zeroed stats without crashing`() {
        val stats = StatsCalculator.compute(emptyList(), emptyList())
        assertEquals(0.0, stats.distanceMeters, 0.0)
        assertEquals(MovementMode.UNKNOWN, stats.dominantMode)
    }

    @Test
    fun `average speed is computed from trusted segments only`() {
        val points = listOf(
            point(25.0000, 121.0000, 0L, speedMps = 2f),
            point(25.0010, 121.0000, 10_000L, speedMps = 4f),
            // 低可信度 / 被捨棄的點不得影響平均速度（規格 8）
            point(29.0000, 121.0000, 20_000L, reliability = "REJECTED", speedMps = 100f)
        )
        val stats = StatsCalculator.compute(points, emptyList())
        assertEquals(4.0, stats.avgSpeedMps, 0.001)
    }

    @Test
    fun `averageSpeed is distance over moving time`() {
        // 10 km in 1 hour of moving time = 2.777... m/s (10 km/h)
        assertEquals(10_000.0 / 3600.0, StatsCalculator.averageSpeedMps(10_000.0, 3_600_000L), 1e-9)
    }

    @Test
    fun `averageSpeed is zero without moving time or distance`() {
        assertEquals(0.0, StatsCalculator.averageSpeedMps(500.0, 0L), 0.0)
        assertEquals(0.0, StatsCalculator.averageSpeedMps(0.0, 60_000L), 0.0)
    }

    @Test
    fun `final distance prefers the exact snapshot and ignores pause gaps in the recomputed value`() {
        // 重算值 5400 m 包含暫停期間搭車的 5000 m；快照 400 m 才是實際走的距離。
        assertEquals(400.0, StatsCalculator.chooseFinalDistance(5_400.0, 400.0), 0.0)
    }

    @Test
    fun `final distance falls back to the recomputed value when there is no snapshot`() {
        assertEquals(1_234.0, StatsCalculator.chooseFinalDistance(1_234.0, 0.0), 0.0)
    }
}
