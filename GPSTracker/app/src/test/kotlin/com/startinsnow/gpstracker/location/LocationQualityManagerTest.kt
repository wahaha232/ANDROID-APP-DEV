package com.startinsnow.gpstracker.location

import com.startinsnow.gpstracker.core.model.LocationProvider
import com.startinsnow.gpstracker.core.model.LocationQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** GPS Lost / 室內完全沒有定位的判斷（規格 10 / 63）。 */
class LocationQualityManagerTest {

    @Test
    fun `before the first fix timeout it is not reported as lost`() {
        val manager = LocationQualityManager()
        manager.startTracking(nowElapsedRealtimeMs = 1_000L)
        assertFalse(manager.hasFix)
        assertFalse(manager.isLost(10_000L))
    }

    @Test
    fun `never receiving any fix becomes lost after the first fix timeout`() {
        val manager = LocationQualityManager()
        manager.startTracking(1_000L)
        assertTrue(manager.isLost(1_000L + LocationQualityManager.FIRST_FIX_TIMEOUT_MS + 1))
    }

    @Test
    fun `receiving a fix resets the lost timeout and marks hasFix`() {
        val manager = LocationQualityManager()
        manager.startTracking(1_000L)
        manager.onLocationReceived(20_000L)
        assertTrue(manager.hasFix)
        assertFalse(manager.isLost(60_000L))
        assertTrue(manager.isLost(20_000L + LocationQualityManager.DEFAULT_LOST_TIMEOUT_MS + 1))
    }

    @Test
    fun `reset clears fix state so a new track starts clean`() {
        val manager = LocationQualityManager()
        manager.startTracking(0L)
        manager.onLocationReceived(5_000L)
        manager.reset()
        assertFalse(manager.hasFix)
        assertFalse(manager.isLost(100_000L))
    }

    @Test
    fun `network provider is never classified as good quality`() {
        assertEquals(LocationQuality.NORMAL, LocationQualityManager.qualityFor(LocationProvider.NETWORK, 5f))
        assertEquals(LocationQuality.GOOD, LocationQualityManager.qualityFor(LocationProvider.GPS, 5f))
    }

    @Test
    fun `weak accuracy degrades quality`() {
        assertEquals(LocationQuality.WEAK, LocationQualityManager.qualityFor(LocationProvider.GPS, 80f))
    }
}
