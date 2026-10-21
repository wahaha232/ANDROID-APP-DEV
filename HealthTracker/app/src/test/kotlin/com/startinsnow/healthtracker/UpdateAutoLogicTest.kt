package com.startinsnow.healthtracker

import com.startinsnow.healthtracker.update.UpdateIntervals
import com.startinsnow.healthtracker.update.UpdateLogicAuto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateAutoLogicTest {
    @Test fun pendingInstallNeedsNewerVersionAndFile() {
        assertTrue(UpdateLogicAuto.pendingInstall(330, true, 329))
        assertFalse(UpdateLogicAuto.pendingInstall(330, true, 330)) // 已經裝了
        assertFalse(UpdateLogicAuto.pendingInstall(330, false, 329)) // 檔案被清掉
        assertFalse(UpdateLogicAuto.pendingInstall(0, false, 329))
    }

    @Test fun notifiesOncePerVersion() {
        assertTrue(UpdateLogicAuto.shouldNotify(331, 330, 329))
        assertFalse(UpdateLogicAuto.shouldNotify(331, 331, 329))
        assertFalse(UpdateLogicAuto.shouldNotify(329, 0, 329))
    }

    @Test fun downloadsOnlyWhenVersionChangedOrFileMissing() {
        assertTrue(UpdateLogicAuto.shouldDownload(331, 330, true))
        assertTrue(UpdateLogicAuto.shouldDownload(331, 331, false))
        assertFalse(UpdateLogicAuto.shouldDownload(331, 331, true))
    }

    @Test fun intervalOptionsAndLabels() {
        assertEquals(listOf(0, 6, 12, 24, 72, 168), UpdateIntervals.OPTIONS)
        assertEquals("僅手動", UpdateIntervals.label(0))
        assertEquals("每週", UpdateIntervals.label(168))
        assertEquals(0, UpdateIntervals.sanitize(5))
        assertEquals(24, UpdateIntervals.sanitize(24))
    }
}
