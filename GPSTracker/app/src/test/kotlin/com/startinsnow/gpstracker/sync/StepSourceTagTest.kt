package com.startinsnow.gpstracker.sync

import com.startinsnow.gpstracker.data.prefs.StepSource
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class StepSourceTagTest {

    @Test
    fun forSource_mapsEachStepSourceToItsTag() {
        assertEquals(StepSourceTag.ZEPP_LIFE, StepSourceTag.forSource(StepSource.ZEPP_LIFE))
        assertEquals(StepSourceTag.GOOGLE_FIT, StepSourceTag.forSource(StepSource.GOOGLE_FIT))
        assertEquals(StepSourceTag.SENSOR, StepSourceTag.forSource(StepSource.DEVICE_SENSOR))
    }

    @Test
    fun label_unknownTagFallsBackToSensor() {
        assertEquals(StepSourceTag.label(StepSourceTag.SENSOR), StepSourceTag.label("something-else"))
    }

    @Test
    fun label_fallbackTagHintsThatResyncIsPossible() {
        assertTrue(StepSourceTag.label(StepSourceTag.SENSOR_FALLBACK).contains("重新同步"))
    }
}
