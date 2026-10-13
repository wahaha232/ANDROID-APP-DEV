package com.startinsnow.gpstracker.core.analysis

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecapCalculatorTest {
    private val zone = ZoneId.of("Asia/Taipei")
    private var seq = 0

    private fun trk(y: Int, m: Int, d: Int, km: Double, mode: String = "WALKING", maxSpeedMps: Double = 5.0, durationMs: Long = 3_600_000L) =
        TrackSummary(
            trackId = "t${++seq}",
            startTimeMs = LocalDate.of(y, m, d).atTime(9, 0).atZone(zone).toInstant().toEpochMilli(),
            distanceMeters = km * 1000, durationMs = durationMs, ascentMeters = 10.0, steps = 100, maxSpeedMps = maxSpeedMps, mode = mode
        )

    private val tracks = listOf(
        trk(2025, 12, 31, 5.0), trk(2025, 6, 1, 20.0),
        trk(2026, 1, 1, 10.0), trk(2026, 1, 2, 12.0), trk(2026, 1, 3, 8.0), trk(2026, 1, 10, 30.0, "BICYCLE", 9.0),
        trk(2026, 3, 5, 15.0, maxSpeedMps = 500.0) // 500 m/s = 1800 km/h：GPS 異常，不能列為最快
    )

    @Test
    fun years_areNewestFirst() = assertEquals(listOf(2026, 2025), RecapCalculator.years(tracks, zone))

    @Test
    fun wholeYear_totalsBucketsAndPreviousYear() {
        val r = RecapCalculator.compute(tracks, 2026, 0, zone)
        assertEquals(5, r.totals.count)
        assertEquals(75.0, r.totals.km, 1e-9)
        assertEquals(5, r.totals.activeDays)
        assertEquals(12, r.buckets.size)
        assertEquals(60.0, r.buckets[0].km, 1e-9)
        assertEquals(4, r.buckets[0].count)
        assertEquals(r.totals.km, r.buckets.sumOf { it.km }, 1e-9)
        assertEquals(2025 to 0, r.previousYear to r.previousMonth)
        assertEquals(25.0, r.previous.km, 1e-9)
    }

    @Test
    fun singleMonth_usesDays_andPreviousMonthWrapsToLastDecember() {
        val r = RecapCalculator.compute(tracks, 2026, 1, zone)
        assertEquals(31, r.buckets.size)
        assertEquals(30.0, r.buckets[9].km, 1e-9)
        assertEquals(2025 to 12, r.previousYear to r.previousMonth)
        assertEquals(5.0, r.previous.km, 1e-9)
        assertEquals(28, RecapCalculator.compute(tracks, 2026, 2, zone).buckets.size)
        assertEquals(29, RecapCalculator.compute(tracks, 2024, 2, zone).buckets.size)
        assertEquals(2026 to 6, RecapCalculator.previousPeriod(2026, 7))
    }

    @Test
    fun emptyPeriod_isAllZeros_withoutInventingHighlights() {
        val r = RecapCalculator.compute(tracks, 2020, 0, zone)
        assertEquals(0, r.totals.count)
        assertEquals(0.0, r.totals.km, 0.0)
        assertNull(r.longest); assertNull(r.fastest); assertNull(r.busiestDay)
        assertTrue(r.top.isEmpty()); assertTrue(r.modes.isEmpty())
        assertEquals(0, r.streakDays)
        assertEquals(0, RecapCalculator.compute(emptyList(), 2026, 3, zone).totals.count)
    }

    @Test
    fun highlights_longest_fastestWithoutAnomalies_busiest_streak_top5() {
        val r = RecapCalculator.compute(tracks, 2026, 0, zone)
        assertEquals(30000.0, r.longest!!.distanceMeters, 0.0)
        assertEquals(9.0, r.fastest!!.maxSpeedMps, 0.0)
        assertEquals(30.0, r.busiestDay!!.km, 1e-9)
        assertEquals(LocalDate.of(2026, 1, 10), r.busiestDay!!.date)
        assertEquals(3, r.streakDays)
        assertEquals(listOf(30.0, 15.0, 12.0, 10.0, 8.0), r.top.map { it.distanceMeters / 1000 })
        assertEquals(2, RecapCalculator.longestStreak(listOf(trk(2026, 2, 28, 1.0), trk(2026, 3, 1, 1.0)), zone))
        assertEquals(1, RecapCalculator.longestStreak(listOf(trk(2026, 5, 1, 1.0), trk(2026, 5, 1, 2.0)), zone))
    }

    @Test
    fun modeShares_sumTo100_sortedByDistance() {
        val r = RecapCalculator.compute(tracks, 2026, 0, zone)
        assertEquals(listOf("WALKING", "BICYCLE"), r.modes.map { it.mode })
        assertEquals(100.0, r.modes.sumOf { it.percent }, 1e-9)
    }

    @Test
    fun invalidTracks_areExcluded_likeTheWebsite() {
        assertFalse(RecapCalculator.isValid(trk(2026, 1, 1, 0.0)))
        assertFalse(RecapCalculator.isValid(trk(2026, 1, 1, 5.0, durationMs = 0L)))
        assertFalse(RecapCalculator.isValid(trk(2026, 1, 1, 5000.0, durationMs = 3_600_000L))) // 5000 km/h
        assertTrue(RecapCalculator.isValid(trk(2026, 1, 1, 5.0)))
        val withBad = tracks + trk(2026, 1, 20, 5000.0, durationMs = 3_600_000L)
        assertEquals(5, RecapCalculator.compute(withBad, 2026, 0, zone).totals.count)
    }
}
