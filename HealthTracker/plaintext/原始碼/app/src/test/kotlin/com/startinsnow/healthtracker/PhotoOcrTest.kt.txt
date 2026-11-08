package com.startinsnow.healthtracker

import com.startinsnow.healthtracker.ocr.PhotoOcr
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoOcrTest {
    @Test fun noZoomShowsWholePhoto() {
        val c = PhotoOcr.visibleCrop(1000f, 2000f, 1000, 2000, 1f, 0f, 0f)
        assertEquals(0f, c.left, 0.001f); assertEquals(0f, c.top, 0.001f)
        assertEquals(1f, c.right, 0.001f); assertEquals(1f, c.bottom, 0.001f)
    }

    @Test fun zoomTwiceCentredShowsMiddleHalf() {
        val c = PhotoOcr.visibleCrop(1000f, 2000f, 1000, 2000, 2f, 0f, 0f)
        assertEquals(0.25f, c.left, 0.001f); assertEquals(0.25f, c.top, 0.001f)
        assertEquals(0.75f, c.right, 0.001f); assertEquals(0.75f, c.bottom, 0.001f)
    }

    @Test fun panningMovesTheVisibleWindow() {
        // 放大 2 倍後把照片往右拖 250px → 看到的是照片更左邊的部分
        val c = PhotoOcr.visibleCrop(1000f, 2000f, 1000, 2000, 2f, 250f, 0f)
        assertEquals(0.125f, c.left, 0.001f)
        assertEquals(0.625f, c.right, 0.001f)
    }

    @Test fun letterboxedPhotoIsMappedByItsDisplayedArea() {
        // 橫向框內放直向照片（左右留黑）：不放大時整張照片都看得到
        val c = PhotoOcr.visibleCrop(2000f, 1000f, 500, 1000, 1f, 0f, 0f)
        assertEquals(0f, c.left, 0.001f); assertEquals(1f, c.right, 0.001f)
        assertEquals(0f, c.top, 0.001f); assertEquals(1f, c.bottom, 0.001f)
    }

    @Test fun draggingFarOutsideIsClampedToThePhoto() {
        val c = PhotoOcr.visibleCrop(1000f, 2000f, 1000, 2000, 2f, 5000f, 5000f)
        assertEquals(0f, c.left, 0.001f); assertEquals(0f, c.top, 0.001f)
    }

    @Test fun upscaleOnlyForSmallImages() {
        assertEquals(1f, PhotoOcr.upscaleFactor(2000, 1000), 0.001f)
        assertEquals(2f, PhotoOcr.upscaleFactor(700, 400), 0.001f)
        assertEquals(4f, PhotoOcr.upscaleFactor(50, 40), 0.001f)
    }
}
