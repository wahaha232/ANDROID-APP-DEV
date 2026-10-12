package com.startinsnow.gpstracker.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class WebDeletedDetectionTest {

    @Test
    fun tracksUploadedBeforeButAbsentFromTheManifestAreReported() {
        val missing = WebDashboardSync.missingFromManifest(listOf("a", "b", "c"), setOf("a", "c"))
        assertEquals(listOf("b"), missing)
    }

    @Test
    fun nothingIsReportedWhenEverythingIsStillOnTheWebsite() {
        assertEquals(emptyList<String>(), WebDashboardSync.missingFromManifest(listOf("a", "b"), setOf("a", "b", "x")))
    }

    @Test
    fun tracksNeverUploadedAreNotPartOfTheComparison() {
        // 只有「上傳過」的 id 會被傳進來比對；從未上傳的紀錄（webUploadedAtMs = null）不在清單內，所以不會被誤判。
        assertEquals(emptyList<String>(), WebDashboardSync.missingFromManifest(emptyList(), emptySet()))
    }
}
