package com.startinsnow.healthtracker

import com.startinsnow.healthtracker.data.db.HealthRecord
import com.startinsnow.healthtracker.data.db.MeasureContext
import com.startinsnow.healthtracker.data.db.RecordType
import com.startinsnow.healthtracker.data.repository.ReportBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DAY = 86_400_000L
private const val NOW = 1_800_000_000_000L

private fun r(
    id: String, type: RecordType, v: Double, ageDays: Double,
    ctx: MeasureContext? = null, v2: Double? = null, deleted: Boolean = false,
) = HealthRecord(
    id = id, type = type, value = v, value2 = v2, measuredAt = NOW - (ageDays * DAY).toLong(),
    createdAt = 1, updatedAt = 1, deletedAt = if (deleted) 9L else null, context = ctx,
)

class ReportBuilderTest {
    private val all = listOf(
        r("a", RecordType.BLOOD_GLUCOSE, 90.0, 1.0, MeasureContext.FASTING),
        r("b", RecordType.BLOOD_GLUCOSE, 130.0, 1.0, MeasureContext.AFTER_MEAL),
        r("c", RecordType.BLOOD_GLUCOSE, 100.0, 2.0, MeasureContext.FASTING),
        r("d", RecordType.BLOOD_GLUCOSE, 110.0, 3.0),
        r("e", RecordType.BLOOD_GLUCOSE, 500.0, 1.0, deleted = true),
        r("old", RecordType.BLOOD_GLUCOSE, 80.0, 50.0),
        r("w1", RecordType.WEIGHT, 70.0, 1.0),
        r("w2", RecordType.WEIGHT, 72.0, 5.0),
        r("bp", RecordType.BLOOD_PRESSURE, 120.0, 2.0, v2 = 80.0),
    )

    @Test fun onlyTheRequestedPeriodAndNeverDeletedRecords() {
        val d30 = ReportBuilder.build(all, "", null, null, 30, NOW)
        assertEquals(listOf("d", "c", "a", "b").sorted(), d30.glucose.map { it.id }.sorted())
        assertEquals(4, d30.glucoseTotal)
        assertEquals(5, ReportBuilder.build(all, "", null, null, 90, NOW).glucoseTotal)
    }

    @Test fun glucoseRowsAreOldestFirst() {
        val ids = ReportBuilder.build(all, "", null, null, 30, NOW).glucose.map { it.id }
        assertEquals("d", ids.first())
    }

    @Test fun overallStats() {
        val s = ReportBuilder.build(all, "", null, null, 30, NOW).overall!!
        assertEquals(4, s.count)
        assertEquals(107.5, s.avg, 1e-9)
        assertEquals(90.0, s.min, 0.0)
        assertEquals(130.0, s.max, 0.0)
    }

    @Test fun perContextStatsIncludeUnlabeledAndSkipEmptyContexts() {
        val rows = ReportBuilder.build(all, "", null, null, 30, NOW).contexts
        assertEquals(listOf("空腹", "餐後", "未標示"), rows.map { it.label })
        assertEquals(95.0, rows.first().stats.avg, 1e-9)
        assertEquals(2, rows.first().stats.count)
    }

    @Test fun rangeInfoOnlyWhenTheUserSetOne() {
        assertNull(ReportBuilder.build(all, "", null, null, 30, NOW).range)
        val ri = ReportBuilder.build(all, "", 90.0, 120.0, 30, NOW).range!!
        assertEquals(3, ri.stat.inRange)
        assertEquals(4, ri.stat.total)
    }

    @Test fun otherMeasurementsAreSummarised() {
        val o = ReportBuilder.build(all, "", null, null, 30, NOW).others
        assertEquals(listOf("體重", "血壓"), o.map { it.label })
        assertEquals("70.0 kg", o[0].latest)
        assertEquals("71.0 kg", o[0].average)
        assertEquals("120/80 mmHg", o[1].latest)
    }

    @Test fun emptyDataGivesEmptyReportNotFakeNumbers() {
        val d = ReportBuilder.build(emptyList(), "A", null, null, 7, NOW)
        assertTrue(d.glucose.isEmpty())
        assertNull(d.overall)
        assertTrue(d.contexts.isEmpty() && d.others.isEmpty())
        assertEquals("A", d.name)
    }

    @Test fun veryLongPeriodsKeepTheNewestRowsButCountEverything() {
        val many = (0 until ReportBuilder.MAX_ROWS + 50).map { r("g$it", RecordType.BLOOD_GLUCOSE, 100.0, 0.001 * it + 0.01) }
        val d = ReportBuilder.build(many, "", null, null, 30, NOW)
        assertEquals(ReportBuilder.MAX_ROWS, d.glucose.size)
        assertEquals(ReportBuilder.MAX_ROWS + 50, d.glucoseTotal)
        assertNotNull(d.overall)
    }
}
