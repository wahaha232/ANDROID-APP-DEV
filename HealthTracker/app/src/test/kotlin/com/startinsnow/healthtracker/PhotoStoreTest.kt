package com.startinsnow.healthtracker

import com.startinsnow.healthtracker.ocr.PhotoStore
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoStoreTest {
    @Test fun smallerImagesAreNotUpscaled() = assertEquals(1200 to 800, PhotoStore.scaledSize(1200, 800, 1800))

    @Test fun landscapeIsScaledByWidth() = assertEquals(1800 to 1350, PhotoStore.scaledSize(4000, 3000, 1800))

    @Test fun portraitIsScaledByHeight() = assertEquals(1350 to 1800, PhotoStore.scaledSize(3000, 4000, 1800))

    @Test fun neverCollapsesToZero() = assertEquals(1 to 1800, PhotoStore.scaledSize(1, 100000, 1800))

    @Test fun exactlyAtLimitIsUnchanged() = assertEquals(1800 to 900, PhotoStore.scaledSize(1800, 900, 1800))

    private val taipei = java.time.ZoneId.of("Asia/Taipei")

    @Test fun exifTimeUsesPhoneZoneWhenNoOffset() =
        assertEquals(1_760_000_000_000L, PhotoStore.parseExifTime("2025:10:09 16:53:20", null, taipei))

    @Test fun exifTimeHonoursOffset() =
        assertEquals(1_760_000_000_000L, PhotoStore.parseExifTime("2025:10:09 08:53:20", "+00:00", taipei))

    @Test fun badExifTimeIsNull() {
        assertEquals(null, PhotoStore.parseExifTime("0000:00:00 00:00:00", null, taipei))
        assertEquals(null, PhotoStore.parseExifTime("", null, taipei))
        assertEquals(null, PhotoStore.parseExifTime(null, null, taipei))
    }
}
