package com.startinsnow.gpstracker.movement

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPausePolicyTest {

    private fun decide(
        enabled: Boolean = true,
        isPaused: Boolean = false,
        isAutoPaused: Boolean = false,
        stationarySince: Long? = 1_000L,
        now: Long,
        threshold: Long
    ) = AutoPausePolicy.shouldAutoPause(enabled, isPaused, isAutoPaused, stationarySince, now, threshold)

    @Test
    fun pausesOnlyOnceTheConfiguredStationaryTimeIsReached() {
        assertFalse(decide(now = 1_000L + 59_999L, threshold = 60_000L))
        assertTrue(decide(now = 1_000L + 60_000L, threshold = 60_000L))
        assertTrue(decide(now = 1_000L + 61_000L, threshold = 60_000L))
    }

    @Test
    fun followsTheUserSetting_shortAndLong() {
        assertTrue(decide(now = 1_000L + 10_000L, threshold = 10_000L))          // 10 秒
        assertFalse(decide(now = 1_000L + 59_000L, threshold = 10 * 60_000L))    // 設成 10 分鐘時，1 分鐘不會暫停
        assertTrue(decide(now = 1_000L + 10 * 60_000L, threshold = 10 * 60_000L))
        assertFalse(decide(now = 1_000L + 3_599_000L, threshold = 3_600_000L))   // 1 時
        assertTrue(decide(now = 1_000L + 3_600_000L, threshold = 3_600_000L))
    }

    @Test
    fun neverPausesWhenDisabled_alreadyPaused_orNotStationary() {
        assertFalse(decide(enabled = false, now = 10_000_000L, threshold = 10_000L))
        assertFalse(decide(isPaused = true, now = 10_000_000L, threshold = 10_000L))
        assertFalse(decide(isAutoPaused = true, now = 10_000_000L, threshold = 10_000L))
        assertFalse(decide(stationarySince = null, now = 10_000_000L, threshold = 10_000L))
    }
}
