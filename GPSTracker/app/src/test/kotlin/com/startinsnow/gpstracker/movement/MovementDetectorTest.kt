package com.startinsnow.gpstracker.movement

import com.startinsnow.gpstracker.core.geo.GeoMath
import com.startinsnow.gpstracker.core.model.MovementMode
import com.startinsnow.gpstracker.core.model.PointReliability
import org.junit.Assert.assertEquals
import org.junit.Test

class MovementDetectorTest {

    private fun feedConstantSpeed(
        detector: MovementDetector,
        kmh: Double,
        sampleCount: Int,
        intervalMs: Long = 3_000L,
        altitudeMeters: Double? = null,
        startTimeMs: Long = 0L
    ): MovementMode {
        var mode = MovementMode.UNKNOWN
        val speedMps = GeoMath.kmhToMs(kmh)
        for (i in 0 until sampleCount) {
            val result = detector.addSample(
                MovementSample(
                    timestampMs = startTimeMs + i * intervalMs,
                    speedMps = speedMps,
                    accuracyMeters = 8f,
                    altitudeMeters = altitudeMeters,
                    reliability = PointReliability.TRUSTED
                )
            )
            mode = result.mode
        }
        return mode
    }

    @Test
    fun `0 kmh is stationary`() {
        val detector = MovementDetector()
        val mode = feedConstantSpeed(detector, 0.0, sampleCount = 5)
        assertEquals(MovementMode.STATIONARY, mode)
    }

    @Test
    fun `4 kmh is walking`() {
        val detector = MovementDetector()
        val mode = feedConstantSpeed(detector, 4.0, sampleCount = 5)
        assertEquals(MovementMode.WALKING, mode)
    }

    @Test
    fun `20 kmh is bicycle`() {
        val detector = MovementDetector()
        val mode = feedConstantSpeed(detector, 20.0, sampleCount = 5)
        assertEquals(MovementMode.BICYCLE, mode)
    }

    @Test
    fun `100 kmh sustained smoothly is car candidate`() {
        val detector = MovementDetector()
        val mode = feedConstantSpeed(detector, 100.0, sampleCount = 6)
        assertEquals(MovementMode.CAR, mode)
    }

    @Test
    fun `600 kmh with high altitude sustained is airplane candidate`() {
        val detector = MovementDetector()
        val mode = feedConstantSpeed(
            detector,
            kmh = 600.0,
            sampleCount = 8,
            intervalMs = 3_000L,
            altitudeMeters = 9000.0
        )
        assertEquals(MovementMode.AIRPLANE, mode)
    }

    @Test
    fun `single instantaneous high speed sample does not immediately flip mode - hysteresis`() {
        val detector = MovementDetector()
        feedConstantSpeed(detector, 5.0, sampleCount = 5) // establishes WALKING
        val afterOneSpike = detector.addSample(
            MovementSample(
                timestampMs = 20_000L,
                speedMps = GeoMath.kmhToMs(120.0),
                accuracyMeters = 8f,
                altitudeMeters = null,
                reliability = PointReliability.TRUSTED
            )
        )
        // A lone high speed sample should not instantly declare CAR; hysteresis requires stability.
        assertEquals(MovementMode.WALKING, afterOneSpike.mode)
    }

    @Test
    fun `low confidence samples are ignored and do not change current mode`() {
        val detector = MovementDetector()
        feedConstantSpeed(detector, 20.0, sampleCount = 5) // BICYCLE
        val result = detector.addSample(
            MovementSample(
                timestampMs = 30_000L,
                speedMps = GeoMath.kmhToMs(150.0),
                accuracyMeters = 80f,
                altitudeMeters = null,
                reliability = PointReliability.LOW_CONFIDENCE
            )
        )
        assertEquals(MovementMode.BICYCLE, result.mode)
    }

    @Test
    fun `reset clears mode back to unknown`() {
        val detector = MovementDetector()
        feedConstantSpeed(detector, 20.0, sampleCount = 5)
        detector.reset()
        assertEquals(MovementMode.UNKNOWN, detector.currentMode())
    }
}
