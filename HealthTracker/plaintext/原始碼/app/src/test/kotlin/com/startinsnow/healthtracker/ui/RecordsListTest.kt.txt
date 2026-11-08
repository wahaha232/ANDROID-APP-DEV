package com.startinsnow.healthtracker.ui

import com.startinsnow.healthtracker.data.db.HealthRecord
import com.startinsnow.healthtracker.data.db.RecordType
import org.junit.Assert.assertEquals
import org.junit.Test

/** 「紀錄 > 全部」必須列出每一種類型（體重／體脂率／血糖／血壓），不可只列部分。 */
class RecordsListTest {
    private fun rec(id: String, type: RecordType, value: Double, at: Long) = HealthRecord(
        id = id, type = type, value = value, measuredAt = at, createdAt = 0, updatedAt = 0,
    )

    private val list = listOf(
        rec("w", RecordType.WEIGHT, 70.0, 300),
        rec("f", RecordType.BODY_FAT, 20.0, 200),
        rec("g", RecordType.BLOOD_GLUCOSE, 100.0, 100),
    )

    @Test fun allTabIncludesEveryType() {
        val all = recordsForTab(list, null)
        assertEquals(listOf("w", "f", "g"), all.map { it.id })
        assertEquals(
            setOf(RecordType.WEIGHT, RecordType.BODY_FAT, RecordType.BLOOD_GLUCOSE),
            all.map { it.type }.toSet(),
        )
    }

    @Test fun allTabIsSortedNewestFirst() {
        val shuffled = listOf(list[1], list[2], list[0])
        assertEquals(listOf("w", "f", "g"), recordsForTab(shuffled, null).map { it.id })
    }

    @Test fun typeFilterKeepsOnlyThatType() {
        assertEquals(listOf("f"), recordsForTab(list, RecordType.BODY_FAT).map { it.id })
    }

    @Test fun summaryCountsAllTypes() {
        assertEquals(
            mapOf(RecordType.WEIGHT to 1, RecordType.BODY_FAT to 1, RecordType.BLOOD_GLUCOSE to 1),
            typeCounts(list),
        )
        assertEquals("全部 3 筆：體重 1、體脂率 1、血糖 1", summaryText(list))
    }
}
