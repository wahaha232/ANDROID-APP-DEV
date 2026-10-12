package com.startinsnow.gpstracker.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Health Connect 來源 package 的分類（「手機本身」的 package 帶有每支手機不同的隨機碼，只能用前綴判斷）。 */
class StepOriginClassifierTest {

    @Test
    fun phone_isRecognisedByPrefixDespiteTheRandomSuffix() {
        assertTrue(HealthConnectStepReader.isPhone("com.android.healthconnect.phone.j2981a4d0733d3369ba753b424c464bdb"))
        assertTrue(HealthConnectStepReader.isPhone("com.android.healthconnect.phone.anything"))
        assertFalse(HealthConnectStepReader.isPhone("com.google.android.apps.fitness"))
    }

    @Test
    fun googleFit_andZepp_areMatchedExactly() {
        assertTrue(HealthConnectStepReader.isGoogleFit("com.google.android.apps.fitness"))
        assertTrue(HealthConnectStepReader.isZepp("com.xiaomi.hm.health"))
        assertTrue(HealthConnectStepReader.isZepp("com.huami.watch.hmwatchmanager"))
        assertFalse(HealthConnectStepReader.isZepp("com.fitbit.FitbitMobile"))
        assertFalse(HealthConnectStepReader.isGoogleFit("com.google.android.apps.fitness.extra"))
    }
}
