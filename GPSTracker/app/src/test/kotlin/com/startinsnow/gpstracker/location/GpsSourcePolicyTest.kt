package com.startinsnow.gpstracker.location

import com.startinsnow.gpstracker.core.model.LocationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsSourcePolicyTest {

    @Test
    fun `network fix is ignored while gps is fresh`() {
        assertTrue(GpsSourcePolicy.shouldIgnoreNetworkFix(LocationProvider.NETWORK, 20_000L, 10_000L))
    }

    @Test
    fun `network fix is accepted once gps has been silent past the window`() {
        val last = 10_000L
        assertFalse(GpsSourcePolicy.shouldIgnoreNetworkFix(LocationProvider.NETWORK, last + GpsSourcePolicy.GPS_FRESH_WINDOW_MS, last))
    }

    @Test
    fun `network fix is accepted when gps never fixed`() {
        assertFalse(GpsSourcePolicy.shouldIgnoreNetworkFix(LocationProvider.NETWORK, 5_000L, Long.MIN_VALUE))
    }

    @Test
    fun `gps fixes are never ignored by this rule`() {
        assertFalse(GpsSourcePolicy.shouldIgnoreNetworkFix(LocationProvider.GPS, 11_000L, 10_000L))
    }
}
