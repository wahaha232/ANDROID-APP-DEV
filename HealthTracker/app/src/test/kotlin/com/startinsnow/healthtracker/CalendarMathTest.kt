package com.startinsnow.healthtracker

import com.startinsnow.healthtracker.data.db.HealthRecord
import com.startinsnow.healthtracker.data.db.RecordType
import com.startinsnow.healthtracker.data.repository.CalendarMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

class CalendarMathTest {
    private val zone = ZoneId.of("Asia/Taipei")

    private fun rec(y: Int, m: Int, d: Int, h: Int, min: Int) = HealthRecord(
        id = "$y$m$d$h$min", type = RecordType.BLOOD_GLUCOSE, value = 100.0,
        measuredAt = ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli(), createdAt = 1, updatedAt = 1,
    )

    @Test fun monthGridStartsOnSunday() {
        // 2026-10-01 是星期四 → 前面補 4 格（日一二三）
        val g = CalendarMath.monthGrid(YearMonth.of(2026, 10))
        assertEquals(4, g.takeWhile { it == null }.size)
        assertEquals(LocalDate.of(2026, 10, 1), g[4])
        assertEquals(4 + 31, g.size)
    }

    @Test fun monthStartingOnSundayHasNoLeadingBlanks() {
        val g = CalendarMath.monthGrid(YearMonth.of(2026, 2)) // 2026-02-01 是星期日
        assertEquals(LocalDate.of(2026, 2, 1), g[0])
        assertEquals(28, g.size)
    }

    @Test fun countsGroupByLocalDay() {
        val c = CalendarMath.countsByDay(listOf(rec(2026, 10, 20, 7, 30), rec(2026, 10, 20, 23, 59), rec(2026, 10, 19, 8, 0)), zone)
        assertEquals(2, c[LocalDate.of(2026, 10, 20)])
        assertEquals(1, c[LocalDate.of(2026, 10, 19)])
        assertNull(c[LocalDate.of(2026, 10, 18)])
    }

    @Test fun levelCapsAtThree() {
        assertEquals(0, CalendarMath.level(0))
        assertEquals(2, CalendarMath.level(2))
        assertEquals(3, CalendarMath.level(9))
    }

    @Test fun dayDotsPlaceRowAndTimeFraction() {
        val today = LocalDate.of(2026, 10, 20)
        val dots = CalendarMath.dayDots(listOf(rec(2026, 10, 20, 12, 0), rec(2026, 10, 18, 6, 0), rec(2026, 10, 1, 6, 0)), 7, today, zone)
        assertEquals(2, dots.size) // 10/01 超出 7 列
        assertEquals(0, dots[0].row)
        assertEquals(0.5f, dots[0].fraction, 0.0001f)
        assertEquals(2, dots[1].row)
        assertEquals(0.25f, dots[1].fraction, 0.0001f)
    }

    @Test fun futureDaysAreDropped() {
        val dots = CalendarMath.dayDots(listOf(rec(2026, 10, 21, 12, 0)), 7, LocalDate.of(2026, 10, 20), zone)
        assertEquals(0, dots.size)
    }
}
