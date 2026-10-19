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
}
