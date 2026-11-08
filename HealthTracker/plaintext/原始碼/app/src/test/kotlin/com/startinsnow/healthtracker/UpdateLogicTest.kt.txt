package com.startinsnow.healthtracker

import com.startinsnow.healthtracker.update.UpdateInfo
import com.startinsnow.healthtracker.update.UpdateLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdateLogicTest {
    private val sha = "a".repeat(64)

    @Test fun parsesValidInfo() {
        val i = UpdateLogic.parse("""{"versionCode":330,"versionName":"1.0.330","apk":"app-1.0.330.apk","sha256":"$sha","size":33000000,"notes":"圖表","package":"x.y"}""")
        assertNotNull(i)
        assertEquals(330, i!!.versionCode)
        assertEquals("x.y", i.packageName)
    }

    @Test fun ignoresUnknownFields() =
        assertNotNull(UpdateLogic.parse("""{"versionCode":1,"versionName":"a","apk":"a.apk","sha256":"$sha","future":true}"""))

    @Test fun rejectsBadOrIncompleteInfo() {
        assertNull(UpdateLogic.parse("not json"))
        assertNull(UpdateLogic.parse("""{"versionCode":0,"versionName":"a","apk":"a.apk","sha256":"$sha"}"""))
        assertNull(UpdateLogic.parse("""{"versionCode":5,"versionName":"a","apk":"","sha256":"$sha"}"""))
        assertNull(UpdateLogic.parse("""{"versionCode":5,"versionName":"a","apk":"a.apk","sha256":"short"}"""))
    }

    @Test fun newerOnlyWhenVersionCodeIsHigher() {
        val i = UpdateInfo(330, "1.0.330", "a.apk", sha)
        assertTrue(UpdateLogic.isNewer(i, 329))
        assertFalse(UpdateLogic.isNewer(i, 330))
        assertFalse(UpdateLogic.isNewer(i, 331))
    }

    @Test fun apkNameMustBeASafeSingleFile() {
        assertTrue(UpdateLogic.isSafeApkName("app-1.0.330_arm64.apk"))
        assertFalse(UpdateLogic.isSafeApkName("../evil.apk"))
        assertFalse(UpdateLogic.isSafeApkName("a/b.apk"))
        assertFalse(UpdateLogic.isSafeApkName("evil.sh"))
        assertFalse(UpdateLogic.isSafeApkName(""))
    }

    @Test fun sha256OfKnownContent() {
        val f = File.createTempFile("sha", ".bin")
        try {
            f.writeText("abc")
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", UpdateLogic.sha256Hex(f))
        } finally {
            f.delete()
        }
    }
}
