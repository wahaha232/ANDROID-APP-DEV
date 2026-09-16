package com.startinsnow.gpstracker.location

import com.startinsnow.gpstracker.core.model.BatteryMode
import com.startinsnow.gpstracker.core.model.LocationQuality
import com.startinsnow.gpstracker.core.model.MovementMode
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 驗證「真的有 Adaptive Sampling」而不是永遠高頻取樣（規格 7 / 54 / 55）。
 */
class AdaptiveSamplingPolicyTest {

    @Test
    fun `stationary sampling is much slower than driving`() {
        val stationary = AdaptiveSamplingPolicy.plan(MovementMode.STATIONARY, LocationQuality.GOOD, BatteryMode.NORMAL)
        val car = AdaptiveSamplingPolicy.plan(MovementMode.CAR, LocationQuality.GOOD, BatteryMode.NORMAL)
        assertTrue(
            "stationary=${stationary.minIntervalMs} car=${car.minIntervalMs}",
            stationary.minIntervalMs >= car.minIntervalMs * 5
        )
    }

    @Test
    fun `low battery increases the sampling interval`() {
        val normal = AdaptiveSamplingPolicy.plan(MovementMode.WALKING, LocationQuality.GOOD, BatteryMode.NORMAL)
        val saver = AdaptiveSamplingPolicy.plan(MovementMode.WALKING, LocationQuality.GOOD, BatteryMode.POWER_SAVING)
        val ultra = AdaptiveSamplingPolicy.plan(MovementMode.WALKING, LocationQuality.GOOD, BatteryMode.ULTRA_POWER_SAVING)
        assertTrue(saver.minIntervalMs > normal.minIntervalMs)
        assertTrue(ultra.minIntervalMs > saver.minIntervalMs)
    }

    @Test
    fun `a long stationary period extends the interval but stays within the maximum`() {
        val fresh = AdaptiveSamplingPolicy.plan(MovementMode.STATIONARY, LocationQuality.GOOD, BatteryMode.NORMAL, 0L)
        val long = AdaptiveSamplingPolicy.plan(
            MovementMode.STATIONARY, LocationQuality.GOOD, BatteryMode.NORMAL, 10 * 60_000L
        )
        assertTrue(long.minIntervalMs > fresh.minIntervalMs)
        assertTrue(long.minIntervalMs <= AdaptiveSamplingPolicy.MAX_INTERVAL_MS)
    }

    @Test
    fun `poor quality never allows a faster interval than the quality floor`() {
        val weak = AdaptiveSamplingPolicy.plan(MovementMode.CAR, LocationQuality.WEAK, BatteryMode.NORMAL)
        assertTrue("weak=${weak.minIntervalMs}", weak.minIntervalMs >= 10_000L)
        val lost = AdaptiveSamplingPolicy.plan(MovementMode.CAR, LocationQuality.LOST, BatteryMode.NORMAL)
        assertTrue("lost=${lost.minIntervalMs}", lost.minIntervalMs >= 15_000L)
    }

    @Test
    fun `every mode stays between one second and the maximum interval`() {
        MovementMode.values().forEach { mode ->
            val plan = AdaptiveSamplingPolicy.plan(mode, LocationQuality.GOOD, BatteryMode.NORMAL)
            assertTrue("$mode interval=${plan.minIntervalMs}", plan.minIntervalMs >= 1_000L)
            assertTrue("$mode interval=${plan.minIntervalMs}", plan.minIntervalMs <= AdaptiveSamplingPolicy.MAX_INTERVAL_MS)
        }
    }

    @Test
    fun `distance threshold grows with the speed of the mode`() {
        val walking = AdaptiveSamplingPolicy.plan(MovementMode.WALKING, LocationQuality.GOOD, BatteryMode.NORMAL)
        val car = AdaptiveSamplingPolicy.plan(MovementMode.CAR, LocationQuality.GOOD, BatteryMode.NORMAL)
        assertTrue(car.minDistanceMeters > walking.minDistanceMeters)
    }
}
