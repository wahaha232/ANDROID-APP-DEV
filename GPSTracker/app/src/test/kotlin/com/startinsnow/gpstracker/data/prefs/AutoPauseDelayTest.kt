package com.startinsnow.gpstracker.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPauseDelayTest {

    @Test
    fun defaultIsOneMinute_andIsValid() {
        assertEquals(60, AutoPauseDelay.DEFAULT_SECONDS)
        assertTrue(AutoPauseDelay.isValid(AutoPauseDelay.DEFAULT_SECONDS.toLong()))
    }

    @Test
    fun splitsSecondsIntoHoursMinutesSeconds() {
        assertEquals(AutoPauseDelay.Hms(0, 1, 0), AutoPauseDelay.toHms(60))
        assertEquals(AutoPauseDelay.Hms(0, 1, 30), AutoPauseDelay.toHms(90))
        assertEquals(AutoPauseDelay.Hms(1, 0, 0), AutoPauseDelay.toHms(3600))
        assertEquals(AutoPauseDelay.Hms(2, 3, 4), AutoPauseDelay.toHms(2 * 3600 + 3 * 60 + 4))
        assertEquals(AutoPauseDelay.Hms(23, 59, 59), AutoPauseDelay.toHms(AutoPauseDelay.MAX_SECONDS))
    }

    @Test
    fun joiningAndSplittingRoundTrips() {
        for (total in listOf(10, 59, 60, 61, 3599, 3600, 3661, 86399)) {
            val hms = AutoPauseDelay.toHms(total)
            assertEquals(total.toLong(), AutoPauseDelay.totalSeconds(hms.hours.toLong(), hms.minutes.toLong(), hms.seconds.toLong()))
        }
    }

    @Test
    fun rangeIs10SecondsTo23h59m59s() {
        assertFalse(AutoPauseDelay.isValid(0))
        assertFalse(AutoPauseDelay.isValid(9))
        assertTrue(AutoPauseDelay.isValid(10))
        assertTrue(AutoPauseDelay.isValid(86_399))
        assertFalse(AutoPauseDelay.isValid(86_400))
    }

    @Test
    fun valuesAreClampedIntoRange() {
        assertEquals(AutoPauseDelay.MIN_SECONDS, AutoPauseDelay.clamp(0))
        assertEquals(AutoPauseDelay.MIN_SECONDS, AutoPauseDelay.clamp(-5))
        assertEquals(AutoPauseDelay.MAX_SECONDS, AutoPauseDelay.clamp(Int.MAX_VALUE))
        assertEquals(90, AutoPauseDelay.clamp(90))
        assertEquals(10_000L, AutoPauseDelay.toMillis(10))
        assertEquals(AutoPauseDelay.MIN_SECONDS * 1000L, AutoPauseDelay.toMillis(0))
    }

    @Test
    fun hugeTypedNumbersDoNotOverflowOrSneakIntoTheValidRange() {
        // 5 位數的「時」＝ 99999 × 3600 遠超出上限，必須判為無效（用 Long 計算，不會溢位成小的正數）。
        assertFalse(AutoPauseDelay.isValid(AutoPauseDelay.totalSeconds(99_999, 99_999, 99_999)))
        assertFalse(AutoPauseDelay.isValid(AutoPauseDelay.totalSeconds(0, 0, 0)))
        assertTrue(AutoPauseDelay.isValid(AutoPauseDelay.totalSeconds(0, 0, 10)))
    }
}
