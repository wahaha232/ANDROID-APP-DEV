package com.startinsnow.healthtracker

import com.startinsnow.healthtracker.data.db.HealthRecord
import com.startinsnow.healthtracker.data.db.RecordType
import com.startinsnow.healthtracker.data.repository.OverviewMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DAY = 86_400_000L
private const val NOW = 1_800_000_000_000L

private fun rec(id: String, type: RecordType, value: Double, ageDays: Double, deleted: Boolean = false) = HealthRecord(
    id = id, type = type, value = value, measuredAt = NOW - (ageDays * DAY).toLong(),
    createdAt = 1, updatedAt = 1, deletedAt = if (deleted) 5L else null,
)

class OverviewMathTest {
    private val all = listOf(
        rec("a", RecordType.BLOOD_GLUCOSE, 100.0, 0.1),
        rec("b", RecordType.BLOOD_GLUCOSE, 120.0, 2.0),
        rec("c", RecordType.BLOOD_GLUCOSE, 90.0, 10.0),
        rec("d", RecordType.BLOOD_GLUCOSE, 300.0, 1.0, deleted = true),
        rec("e", RecordType.WEIGHT, 70.0, 0.5),
    )

    @Test fun latestIgnoresDeletedAndOtherTypes() {
        assertEquals("a", OverviewMath.latest(all, RecordType.BLOOD_GLUCOSE)!!.id)
        assertEquals("e", OverviewMath.latest(all, RecordType.WEIGHT)!!.id)
        assertNull(OverviewMath.latest(all, RecordType.BLOOD_PRESSURE))
    }

    @Test fun windowKeepsOnlyRecentSortedOldestFirst() {
        assertEquals(listOf("b", "a"), OverviewMath.window(all, RecordType.BLOOD_GLUCOSE, 7, NOW).map { it.id })
        assertEquals(listOf("c", "b", "a"), OverviewMath.window(all, RecordType.BLOOD_GLUCOSE, 30, NOW).map { it.id })
    }

    @Test fun windowDropsTimesFarInTheFuture() {
        val bad = all + rec("f", RecordType.BLOOD_GLUCOSE, 111.0, -3.0)
        assertTrue(OverviewMath.window(bad, RecordType.BLOOD_GLUCOSE, 7, NOW).none { it.id == "f" })
    }

    @Test fun statsOverTheWindow() {
        val s = OverviewMath.stats(OverviewMath.window(all, RecordType.BLOOD_GLUCOSE, 30, NOW))!!
        assertEquals(3, s.count)
        assertEquals((100.0 + 120.0 + 90.0) / 3, s.avg, 1e-9)
        assertEquals(90.0, s.min, 0.0)
        assertEquals(120.0, s.max, 0.0)
        assertNull(OverviewMath.stats(emptyList()))
    }

    @Test fun niceScaleCoversRangeWithRoundTicks() {
        val s = OverviewMath.niceScale(88.0, 131.0)
        assertTrue(s.min <= 88.0 && s.max >= 131.0)
        val step = s.ticks[1] - s.ticks[0]
        assertTrue(listOf(5.0, 10.0, 20.0, 25.0, 50.0).any { OverviewMath.close(step, it) })
        assertTrue(s.ticks.size in 3..8)
    }

    @Test fun niceScaleHandlesFlatAndInvalidInput() {
        val flat = OverviewMath.niceScale(100.0, 100.0)
        assertTrue(flat.max > flat.min)
        assertEquals(listOf(0.0, 1.0), OverviewMath.niceScale(Double.NaN, 3.0).ticks)
    }

    @Test fun relativeAgeWording() {
        assertEquals("剛剛", OverviewMath.relativeAge(NOW - 30_000, NOW))
        assertEquals("5 分鐘前", OverviewMath.relativeAge(NOW - 5 * 60_000, NOW))
        assertEquals("3 小時前", OverviewMath.relativeAge(NOW - 3 * 3_600_000L, NOW))
        assertEquals("2 天前", OverviewMath.relativeAge(NOW - 2 * DAY - 1000, NOW))
        assertEquals("剛剛", OverviewMath.relativeAge(NOW + 1000, NOW))
    }
}
