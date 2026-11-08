package com.startinsnow.healthtracker.importer

import com.startinsnow.healthtracker.data.db.RecordSource
import com.startinsnow.healthtracker.data.db.RecordType
import com.startinsnow.healthtracker.data.db.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

private val TAIPEI = ZoneId.of("Asia/Taipei")
private const val NOW = 1_700_000_000_000L

class CsvImporterTest {
    private fun parse(text: String) = CsvImporter.parse(text, TAIPEI, NOW)

    @Test fun parsesEnglishHeaderAndRows() {
        val r = parse("measured_at,type,value,note\n2026-10-19 09:27,WEIGHT,70.5,晨起\n2026-10-19 09:30,BODY_FAT,21.3,\n")
        assertTrue(r.errors.isEmpty())
        assertEquals(2, r.records.size)
        val w = r.records[0]
        assertEquals(RecordType.WEIGHT, w.type)
        assertEquals(70.5, w.value, 0.0)
        assertEquals("晨起", w.note)
        assertEquals(RecordSource.IMPORT, w.source)
        assertEquals(SyncState.PENDING, w.syncState)
        // 2026-10-19 09:27 台北時間 = 01:27 UTC
        assertEquals(java.time.Instant.parse("2026-10-19T01:27:00Z").toEpochMilli(), w.measuredAt)
    }

    @Test fun parsesChineseHeaderAndTypes() {
        val r = parse("時間,類型,數值\n2026/10/19 08:00,體重,69.8\n2026/10/19 08:01,體脂率,20\n2026/10/19 08:05,血糖,95")
        assertTrue(r.errors.isEmpty())
        assertEquals(listOf(RecordType.WEIGHT, RecordType.BODY_FAT, RecordType.BLOOD_GLUCOSE), r.records.map { it.type })
    }

    @Test fun glucoseContextColumnIsParsedByLabelOrCode() {
        val r = parse("時間,類型,數值,情境\n2026-10-19 07:00,血糖,95,空腹\n2026-10-19 09:00,血糖,130,after_meal\n2026-10-19 10:00,血糖,100,\n2026-10-19 10:05,體重,70,空腹")
        assertTrue(r.errors.isEmpty())
        assertEquals(
            listOf(com.startinsnow.healthtracker.data.db.MeasureContext.FASTING, com.startinsnow.healthtracker.data.db.MeasureContext.AFTER_MEAL, null, null),
            r.records.map { it.context },
        )
    }

    @Test fun unknownContextIsReportedWithLineNumber() {
        val r = parse("時間,類型,數值,情境\n2026-10-19 07:00,血糖,95,下午茶")
        assertTrue(r.records.isEmpty())
        assertTrue(r.errors.single().startsWith("第 2 列") && r.errors.single().contains("情境"))
    }

    @Test fun bloodPressureNeedsValue2() {
        val r = parse("time,type,value,value2\n2026-10-19 08:00,血壓,120,80\n2026-10-19 08:10,血壓,120,")
        assertEquals(1, r.records.size)
        assertEquals(80.0, r.records[0].value2!!, 0.0)
        assertEquals(1, r.errors.size)
        assertTrue(r.errors[0].startsWith("第 3 列"))
    }

    @Test fun badRowsReportedWithLineNumbersAndOthersStillImported() {
        val r = parse("時間,類型,數值\n2026-10-19 08:00,體重,705\n昨天,體重,70\n2026-10-19 08:00,心情,5\n2026-10-19 08:00,體重,70")
        assertEquals(1, r.records.size)
        assertEquals(3, r.errors.size)
        assertTrue(r.errors[0].startsWith("第 2 列"))
        assertTrue(r.errors[1].startsWith("第 3 列"))
        assertTrue(r.errors[2].startsWith("第 4 列"))
    }

    @Test fun missingRequiredColumnsRejected() {
        val r = parse("date,weight\n2026-10-19,70")
        assertTrue(r.records.isEmpty())
        assertTrue(r.errors.single().contains("標題列"))
    }

    @Test fun emptyFile() = assertEquals("檔案是空的", parse("").errors.single())

    @Test fun bomAndCrLfAndBlankLinesHandled() {
        val r = parse("﻿時間,類型,數值\r\n2026-10-19 08:00,體重,70\r\n\r\n2026-10-20 08:00,體重,71\r\n")
        assertEquals(2, r.records.size)
    }

    @Test fun quotedFieldsWithCommasAndEscapedQuotes() {
        val r = parse("時間,類型,數值,備註\n2026-10-19 08:00,體重,70,\"早餐前, \"\"空腹\"\"\"")
        assertEquals("早餐前, \"空腹\"", r.records.single().note)
    }

    @Test fun dateOnlyMeansMidnight() {
        val r = parse("時間,類型,數值\n2026-10-19,體重,70")
        assertEquals(java.time.Instant.parse("2026-10-18T16:00:00Z").toEpochMilli(), r.records.single().measuredAt)
    }

    @Test fun epochMillisAccepted() {
        assertEquals(1_760_000_000_000L, CsvImporter.parseTime("1760000000000", TAIPEI))
        assertNull(CsvImporter.parseTime("12345", TAIPEI))
    }

    @Test fun sameRowGivesSameIdAndDifferentRowDifferentId() {
        val a = parse("時間,類型,數值\n2026-10-19 08:00,體重,70").records.single().id
        val b = parse("時間,類型,數值\n2026-10-19 08:00,體重,70").records.single().id
        val c = parse("時間,類型,數值\n2026-10-19 08:00,體重,70.1").records.single().id
        assertEquals(a, b)
        assertNotEquals(a, c)
    }
}

class HealthConnectMappingTest {
    private fun sample(id: String, type: RecordType, v: Double, origin: String = "com.example.scale") =
        HcSample(id, type, v, 1_000, origin)

    @Test fun mapsWeightWithDeterministicIdAndOriginNote() {
        val r = HealthConnectMapping.toRecord(sample("hc-1", RecordType.WEIGHT, 70.2), NOW)!!
        assertEquals(ImportIds.fromHealthConnect("hc-1"), r.id)
        assertEquals(RecordSource.HEALTH_CONNECT, r.source)
        assertEquals("來源：com.example.scale", r.note)
        assertEquals(SyncState.PENDING, r.syncState)
    }

    @Test fun sameHealthConnectIdAlwaysSameRecordId() {
        assertEquals(ImportIds.fromHealthConnect("x"), ImportIds.fromHealthConnect("x"))
        assertNotEquals(ImportIds.fromHealthConnect("x"), ImportIds.fromHealthConnect("y"))
    }

    @Test fun absurdValuesAreDropped() {
        assertNull(HealthConnectMapping.toRecord(sample("a", RecordType.WEIGHT, 7050.0), NOW))
        assertNull(HealthConnectMapping.toRecord(sample("b", RecordType.BODY_FAT, 0.2), NOW))
    }

    @Test fun countsPerOrigin() {
        val counts = HealthConnectMapping.countByOrigin(
            listOf(
                sample("1", RecordType.WEIGHT, 70.0, "b.app"),
                sample("2", RecordType.WEIGHT, 70.0, "a.app"),
                sample("3", RecordType.BODY_FAT, 20.0, "b.app"),
            ),
        )
        assertEquals(mapOf("a.app" to 1, "b.app" to 2), counts)
        assertEquals(listOf("a.app", "b.app"), counts.keys.toList())
    }
}
