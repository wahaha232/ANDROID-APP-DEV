package com.startinsnow.healthtracker

import com.startinsnow.healthtracker.data.repository.MeasureTime
import com.startinsnow.healthtracker.data.repository.RecordValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class MeasureTimeTest {
    private val taipei = ZoneId.of("Asia/Taipei")
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test fun futureToleranceIsFiveMinutes() {
        val now = ms("2026-10-19T04:00:00Z")
        assertFalse(MeasureTime.isFuture(now + 4 * 60_000, now))
        assertFalse(MeasureTime.isFuture(now + 5 * 60_000, now))
        assertTrue(MeasureTime.isFuture(now + 5 * 60_000 + 1, now))
        assertFalse(MeasureTime.isFuture(now - 86_400_000, now))
    }

    @Test fun datePickerMidnightMapsToLocalDate() {
        // 台北 2026-10-19 06:33 = UTC 10/18 22:33；當地日期是 10/19，DatePicker 要的是 10/19 的 UTC 午夜
        val m = ms("2026-10-18T22:33:00Z")
        assertEquals(ms("2026-10-19T00:00:00Z"), MeasureTime.utcMidnightOfLocalDate(m, taipei))
    }

    @Test fun changingDateKeepsTimeOfDay() {
        val m = ms("2026-10-18T22:33:00Z") // 台北 10/19 06:33
        val picked = ms("2026-10-15T00:00:00Z") // 選 10/15
        assertEquals(ms("2026-10-14T22:33:00Z"), MeasureTime.withDate(m, picked, taipei)) // 台北 10/15 06:33
    }

    @Test fun changingTimeKeepsLocalDate() {
        val m = ms("2026-10-18T22:33:00Z") // 台北 10/19 06:33
        assertEquals(ms("2026-10-19T12:05:00Z"), MeasureTime.withTime(m, 20, 5, taipei)) // 台北 10/19 20:05
    }

    @Test fun crossingMidnightBackwardsStaysOnTheSameLocalDay() {
        val m = ms("2026-10-19T15:30:00Z") // 台北 10/19 23:30
        assertEquals(ms("2026-10-18T16:10:00Z"), MeasureTime.withTime(m, 0, 10, taipei)) // 台北 10/19 00:10
    }

    @Test fun validatorRejectsFutureTimes() {
        val now = ms("2026-10-19T04:00:00Z")
        assertNull(RecordValidator.validateTime(now, now))
        assertNotNull(RecordValidator.validateTime(now + 3_600_000, now))
    }
}
