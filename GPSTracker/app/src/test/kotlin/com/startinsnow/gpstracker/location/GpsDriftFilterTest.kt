package com.startinsnow.gpstracker.location

import com.startinsnow.gpstracker.core.model.LocationProvider
import com.startinsnow.gpstracker.core.model.LocationSample
import com.startinsnow.gpstracker.core.model.PointReliability
import org.junit.Assert.assertEquals
import org.junit.Test

class GpsDriftFilterTest {

    private fun sample(
        lat: Double,
        lon: Double,
        elapsedMs: Long,
        accuracy: Float,
        provider: LocationProvider = LocationProvider.GPS
    ) = LocationSample(
        latitude = lat,
        longitude = lon,
        timestampMs = elapsedMs,
        elapsedRealtimeMs = elapsedMs,
        speedMps = null,
        speedAccuracyMps = null,
        altitudeMeters = null,
        altitudeAccuracyMeters = null,
        bearingDegrees = null,
        bearingAccuracyDegrees = null,
        accuracyMeters = accuracy,
        provider = provider,
        trackId = "track-1"
    )

    @Test
    fun `first sample is always trusted`() {
        val filter = GpsDriftFilter()
        val result = filter.evaluate(sample(25.0, 121.0, 0L, 8f))
        assertEquals(PointReliability.TRUSTED, result.reliability)
    }

    @Test
    fun `case1 normal gps 5 to 10m accuracy records normally`() {
        val filter = GpsDriftFilter()
        filter.evaluate(sample(25.0000, 121.0000, 0L, 8f))
        // ~33m movement over 5s => ~6.6 m/s (cycling pace), clearly beyond GPS noise floor, should be trusted.
        val result = filter.evaluate(sample(25.0003, 121.0000, 5_000L, 8f))
        assertEquals(PointReliability.TRUSTED, result.reliability)
    }

    @Test
    fun `case2 poor accuracy 100m is downgraded to low confidence`() {
        val filter = GpsDriftFilter()
        filter.evaluate(sample(25.0000, 121.0000, 0L, 8f))
        val result = filter.evaluate(sample(25.0005, 121.0000, 5_000L, 100f))
        assertEquals(PointReliability.LOW_CONFIDENCE, result.reliability)
    }

    @Test
    fun `case5 drift spike then snap back does not register as trusted high speed`() {
        val filter = GpsDriftFilter()
        filter.evaluate(sample(25.000001, 121.000001, 0L, 6f))
        // Sudden jump of ~78m in 1s claimed with good accuracy => implausible for a pedestrian,
        // but not fast enough to hit the absolute physical-impossibility ceiling, so it must at
        // least be downgraded rather than accepted as truth.
        val jump = filter.evaluate(sample(25.000500, 121.000500, 1_000L, 6f))
        assert(jump.reliability != PointReliability.TRUSTED) {
            "drift spike must not be trusted, was ${jump.reliability}"
        }
    }

    @Test
    fun `case6 indoor network location does not get misread as high speed transit`() {
        val filter = GpsDriftFilter()
        filter.evaluate(sample(25.0000, 121.0000, 0L, 70f, LocationProvider.NETWORK))
        // Implies ~35 km/h but with accuracy far above the trust threshold.
        val result = filter.evaluate(sample(25.0003, 121.0000, 3_000L, 80f, LocationProvider.NETWORK))
        assertEquals(PointReliability.LOW_CONFIDENCE, result.reliability)
    }

    @Test
    fun `impossible teleport speed is rejected outright`() {
        val filter = GpsDriftFilter()
        filter.evaluate(sample(25.0000, 121.0000, 0L, 5f))
        // 500km jump in 1 second is impossible for any real mode of transport.
        val result = filter.evaluate(sample(29.5000, 121.0000, 1_000L, 5f))
        assertEquals(PointReliability.REJECTED, result.reliability)
    }

    @Test
    fun `tiny jitter within combined accuracy circle is not counted as movement`() {
        val filter = GpsDriftFilter()
        filter.evaluate(sample(25.000000, 121.000000, 0L, 10f))
        val result = filter.evaluate(sample(25.000002, 121.000002, 2_000L, 10f))
        assertEquals(PointReliability.LOW_CONFIDENCE, result.reliability)
    }

    @Test
    fun `reset clears anchor so next sample is trusted again`() {
        val filter = GpsDriftFilter()
        filter.evaluate(sample(25.0000, 121.0000, 0L, 8f))
        filter.reset()
        val result = filter.evaluate(sample(40.0, 100.0, 100_000L, 8f))
        assertEquals(PointReliability.TRUSTED, result.reliability)
    }
}
