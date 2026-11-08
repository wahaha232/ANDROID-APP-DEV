package com.startinsnow.healthtracker

import com.startinsnow.healthtracker.data.db.MeasureContext
import com.startinsnow.healthtracker.data.prefs.Sex
import com.startinsnow.healthtracker.data.repository.HealthStandards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthStandardsTest {
    @Test fun bmiUsesTaiwanCutoffs() {
        assertEquals("過瘦", HealthStandards.bmiAssessment(17.0, 30).label)
        assertEquals("正常", HealthStandards.bmiAssessment(22.0, 30).label)
        assertEquals("過重", HealthStandards.bmiAssessment(25.0, 30).label)
        assertEquals("肥胖", HealthStandards.bmiAssessment(28.0, 30).label)
    }

    @Test fun childrenAreNotJudgedByAdultBmi() {
        assertEquals("青少年", HealthStandards.bmiAssessment(22.0, 12).label)
        assertEquals(HealthStandards.Severity.UNKNOWN, HealthStandards.bmiAssessment(22.0, 12).severity)
    }

    @Test fun healthyWeightRangeForHeight() {
        val r = HealthStandards.healthyWeightRange(170.0)!!
        assertEquals(53.5, r.minKg, 0.1)
        assertEquals(69.4, r.maxKg, 0.1)
        assertNull(HealthStandards.healthyWeightRange(null))
    }

    @Test fun bodyFatUsesAgeAndSexBands() {
        assertEquals("過瘦", HealthStandards.bodyFatAssessment(7.0, Sex.MALE, 30)!!.label)
        assertEquals("正常", HealthStandards.bodyFatAssessment(18.0, Sex.MALE, 30)!!.label)
        assertEquals("過重", HealthStandards.bodyFatAssessment(22.0, Sex.MALE, 30)!!.label)
        assertEquals("肥胖", HealthStandards.bodyFatAssessment(30.0, Sex.MALE, 30)!!.label)
        assertEquals("正常", HealthStandards.bodyFatAssessment(30.0, Sex.FEMALE, 45)!!.label)
        assertEquals("肥胖", HealthStandards.bodyFatAssessment(42.0, Sex.FEMALE, 65)!!.label)
        assertNull(HealthStandards.bodyFatAssessment(22.0, Sex.UNSPECIFIED, 30))
        assertNull(HealthStandards.bodyFatAssessment(22.0, Sex.MALE, null))
    }

    @Test fun fastingGlucoseUsesWhoAdaRanges() {
        assertEquals("低血糖", HealthStandards.glucoseAssessment(60.0, MeasureContext.FASTING).label)
        assertEquals("正常", HealthStandards.glucoseAssessment(90.0, MeasureContext.FASTING).label)
        assertEquals("偏高（糖尿病前期）", HealthStandards.glucoseAssessment(110.0, MeasureContext.FASTING).label)
        assertEquals("糖尿病範圍", HealthStandards.glucoseAssessment(130.0, MeasureContext.FASTING).label)
    }

    @Test fun afterMealGlucoseUsesPostprandialRanges() {
        assertEquals("正常", HealthStandards.glucoseAssessment(120.0, MeasureContext.AFTER_MEAL).label)
        assertEquals("偏高（糖尿病前期）", HealthStandards.glucoseAssessment(150.0, MeasureContext.AFTER_MEAL).label)
        assertEquals("糖尿病範圍", HealthStandards.glucoseAssessment(210.0, MeasureContext.AFTER_MEAL).label)
    }

    @Test fun elderlyNoteIsAdded() {
        val a = HealthStandards.glucoseAssessment(100.0, MeasureContext.FASTING, 70)
        assertTrue(a.detail.contains("65 歲以上"))
    }
}
