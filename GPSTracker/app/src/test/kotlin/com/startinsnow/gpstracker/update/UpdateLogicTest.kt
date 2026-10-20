package com.startinsnow.gpstracker.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {
    @Test
    fun versionIsExtractedFromReleaseTitle() {
        assertEquals("1.0.330", UpdateLogic.versionOf("GPS Tracker 1.0.330（最新 debug 版）"))
        assertEquals("1.0.5", UpdateLogic.versionOf("1.0.5"))
        assertNull(UpdateLogic.versionOf("latest"))
    }

    @Test
    fun newerVersionIsDetectedNumerically() {
        assertTrue(UpdateLogic.isNewer("1.0.323", "GPS Tracker 1.0.330（最新 debug 版）"))
        assertTrue(UpdateLogic.isNewer("1.0.99", "1.0.100"))
        assertFalse(UpdateLogic.isNewer("1.0.330", "1.0.330"))
        assertFalse(UpdateLogic.isNewer("1.0.331", "1.0.330"))
        assertFalse(UpdateLogic.isNewer("1.0.1", "no version here"))
    }

    @Test
    fun onlyProjectReleaseDownloadsAreAccepted() {
        val repo = "wahaha232/ANDROID-APP-DEV"
        assertTrue(UpdateLogic.isSafeDownloadUrl("https://github.com/$repo/releases/download/gpstracker-latest/gpstracker-latest.apk", repo))
        assertFalse(UpdateLogic.isSafeDownloadUrl("https://evil.example.com/releases/download/x.apk", repo))
        assertFalse(UpdateLogic.isSafeDownloadUrl("http://github.com/$repo/releases/download/x.apk", repo))
        assertFalse(UpdateLogic.isSafeDownloadUrl("https://github.com/other/repo/releases/download/x.apk", repo))
    }
}
