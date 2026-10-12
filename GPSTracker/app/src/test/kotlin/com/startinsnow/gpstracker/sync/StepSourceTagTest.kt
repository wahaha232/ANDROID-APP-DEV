package com.startinsnow.gpstracker.sync

import com.startinsnow.gpstracker.data.prefs.StepSource
import org.junit.Test
import org.junit.Assert.assertEquals

class StepSourceTagTest {

    @Test
    fun forSource_mapsEachStepSourceToItsTag() {
        assertEquals(StepSourceTag.ZEPP_LIFE, StepSourceTag.forSource(StepSource.ZEPP_LIFE))
        assertEquals(StepSourceTag.GOOGLE_FIT, StepSourceTag.forSource(StepSource.GOOGLE_FIT))
        assertEquals(StepSourceTag.SENSOR, StepSourceTag.forSource(StepSource.DEVICE_SENSOR))
    }

    @Test
    fun labelRes_unknownTagFallsBackToSensor() {
        assertEquals(StepSourceTag.labelRes(StepSourceTag.SENSOR), StepSourceTag.labelRes("something-else"))
    }

    @Test
    fun labelRes_eachTagHasItsOwnLabel() {
        val ids = listOf(
            StepSourceTag.ZEPP_LIFE, StepSourceTag.GOOGLE_FIT, StepSourceTag.SENSOR, StepSourceTag.SENSOR_FALLBACK
        ).map { StepSourceTag.labelRes(it) }
        assertEquals("all four tags map to distinct strings", 4, ids.toSet().size)
    }
}
