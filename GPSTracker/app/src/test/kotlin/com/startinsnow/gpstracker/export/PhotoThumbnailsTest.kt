package com.startinsnow.gpstracker.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoThumbnailsTest {

    @Test
    fun targetSize_shrinksToTheLongEdge_keepsRatio_andNeverEnlarges() {
        assertEquals(960 to 720, PhotoThumbnails.targetSize(4000, 3000))
        assertEquals(720 to 960, PhotoThumbnails.targetSize(3000, 4000))
        assertEquals(800 to 600, PhotoThumbnails.targetSize(800, 600))      // 已經夠小，不放大
        assertEquals(960 to 960, PhotoThumbnails.targetSize(960, 960))
        assertEquals(0 to 0, PhotoThumbnails.targetSize(0, 100))
        val (w, h) = PhotoThumbnails.targetSize(100_000, 10)               // 極端比例也不會變成 0
        assertTrue(w == 960 && h >= 1)
    }

    @Test
    fun sampleSize_isAPowerOfTwo_andDecodesNoSmallerThanTheTarget() {
        assertEquals(1, PhotoThumbnails.sampleSize(800, 600))
        assertEquals(1, PhotoThumbnails.sampleSize(1500, 1000))   // 1500/2 = 750 < 960，不能再縮
        assertEquals(2, PhotoThumbnails.sampleSize(2000, 1500))
        assertEquals(4, PhotoThumbnails.sampleSize(4000, 3000))
        assertEquals(8, PhotoThumbnails.sampleSize(8000, 6000))
        for (long in listOf(961, 1920, 3840, 5000, 12000)) {
            val s = PhotoThumbnails.sampleSize(long, long / 2)
            assertTrue("sample=$s", s >= 1 && (s and (s - 1)) == 0)
            assertTrue("解碼後長邊 ${long / s} 不應小於目標 960", long / s >= 960 || s == 1)
        }
    }

    @Test
    fun relativePath_onlyContainsSafeCharacters() {
        assertEquals("photos/abc-123/p_1.jpg", PhotoThumbnails.relativePath("abc-123", "p_1"))
        val evil = PhotoThumbnails.relativePath("../../etc", "a/b c.jpg")
        assertTrue(evil.startsWith("photos/") && evil.endsWith(".jpg"))
        assertTrue(!evil.contains("..") && !evil.contains("//") && !evil.contains(" ") && evil.count { it == '/' } == 2)
        assertTrue(PhotoThumbnails.relativePath("", "").matches(Regex("photos/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+\\.jpg")))
    }

    @Test
    fun rotation_mapsExifOrientationToDegrees() {
        assertEquals(0, PhotoThumbnails.rotationDegrees(1))
        assertEquals(180, PhotoThumbnails.rotationDegrees(3))
        assertEquals(90, PhotoThumbnails.rotationDegrees(6))
        assertEquals(270, PhotoThumbnails.rotationDegrees(8))
        assertEquals(0, PhotoThumbnails.rotationDegrees(0))
    }
}
