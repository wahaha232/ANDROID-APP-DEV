package com.startinsnow.gpstracker.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/** 逐日步數合併規則：只增不減，舊的天數永遠保留。 */
class DailyStepsMergeTest {

    private fun merge(old: Map<String, Int>, fresh: Map<String, Int>) =
        DailyStepsSync.mergeByDate(old, fresh) { it > 0 }

    @Test
    fun daysMissingFromTheFreshReadAreKept() {
        // Health Connect 日後不再回傳舊資料：已上傳的天數不能消失，累計才不會變少。
        val result = merge(mapOf("2024-04-19" to 100, "2024-04-20" to 200), mapOf("2024-04-20" to 200, "2026-10-12" to 300))
        assertEquals(setOf("2024-04-19", "2024-04-20", "2026-10-12"), result.keys)
        assertEquals(600, result.values.sum())
    }

    @Test
    fun freshValueWinsWhenTheDayHasData() {
        // 當天的步數會隨一天進行而增加，Health Connect 也可能回頭修正：以最新的為準。
        assertEquals(500, merge(mapOf("2026-10-12" to 300), mapOf("2026-10-12" to 500))["2026-10-12"])
    }

    @Test
    fun aFreshEmptyDayNeverWipesAnExistingOne() {
        assertEquals(300, merge(mapOf("2026-10-12" to 300), mapOf("2026-10-12" to 0))["2026-10-12"])
    }

    @Test
    fun newEmptyDaysAreStillRecorded() {
        assertEquals(0, merge(emptyMap(), mapOf("2026-10-13" to 0))["2026-10-13"])
    }

    @Test
    fun theCumulativeTotalNeverDecreasesAcrossMerges() {
        var stored: Map<String, Int> = emptyMap()
        var previousTotal = 0
        listOf(
            mapOf("a" to 10, "b" to 20),
            mapOf("b" to 20, "c" to 30),   // a 已不在讀取範圍
            mapOf("c" to 35)               // 只剩最近一天
        ).forEach { fresh ->
            stored = merge(stored, fresh)
            val total = stored.values.sum()
            assertEquals(true, total >= previousTotal)
            previousTotal = total
        }
        assertEquals(10 + 20 + 35, previousTotal)
    }
}
