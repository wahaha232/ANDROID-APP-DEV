package com.startinsnow.gpstracker.location

import com.startinsnow.gpstracker.core.model.PointReliability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsPowerPolicyTest {

    @Test
    fun movingOrBrieflyStopped_keepsTheConfiguredInterval() {
        assertEquals(2_000L, GpsPowerPolicy.gpsIntervalMs(2_000L, 0L))
        assertEquals(2_000L, GpsPowerPolicy.gpsIntervalMs(2_000L, GpsPowerPolicy.STATIONARY_THRESHOLD_MS - 1))
    }

    @Test
    fun stationaryForAWhile_stretchesTheInterval_butNeverShortensALongerOne() {
        assertEquals(10_000L, GpsPowerPolicy.gpsIntervalMs(2_000L, GpsPowerPolicy.STATIONARY_THRESHOLD_MS))
        assertEquals(10_000L, GpsPowerPolicy.gpsIntervalMs(1_000L, 600_000L)) // 高精度 1 秒 -> 10 秒
        assertEquals(30_000L, GpsPowerPolicy.gpsIntervalMs(30_000L, 600_000L)) // 本來就更長就不縮短
    }

    @Test
    fun idleNoisePointsAreNotPersisted_butEverythingElseIs() {
        val idle = GpsPowerPolicy.STATIONARY_THRESHOLD_MS
        assertFalse(GpsPowerPolicy.shouldPersistPoint(PointReliability.LOW_CONFIDENCE, idle))
        assertTrue(GpsPowerPolicy.shouldPersistPoint(PointReliability.LOW_CONFIDENCE, idle - 1))
        assertTrue("moving: noise points are still kept for debugging/playback", GpsPowerPolicy.shouldPersistPoint(PointReliability.LOW_CONFIDENCE, 0L))
        assertTrue(GpsPowerPolicy.shouldPersistPoint(PointReliability.TRUSTED, idle))
        assertTrue("rejected jumps are kept for debugging", GpsPowerPolicy.shouldPersistPoint(PointReliability.REJECTED, idle))
    }
}
