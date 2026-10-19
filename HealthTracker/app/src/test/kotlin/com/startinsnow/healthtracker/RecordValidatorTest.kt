package com.startinsnow.healthtracker

import com.startinsnow.healthtracker.data.db.RecordType
import com.startinsnow.healthtracker.data.prefs.UserSettings
import com.startinsnow.healthtracker.data.repository.RecordValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RecordValidatorTest {
    @Test fun weightInRangePasses() = assertNull(RecordValidator.validate(RecordType.WEIGHT, 70.5, null))

    @Test fun weightTypoRejected() = assertNotNull(RecordValidator.validate(RecordType.WEIGHT, 705.0, null))

    @Test fun missingValueRejected() = assertNotNull(RecordValidator.validate(RecordType.BLOOD_GLUCOSE, null, null))

    @Test fun pressureNeedsBothValues() = assertNotNull(RecordValidator.validate(RecordType.BLOOD_PRESSURE, 120.0, null))

    @Test fun pressureSystolicMustExceedDiastolic() =
        assertNotNull(RecordValidator.validate(RecordType.BLOOD_PRESSURE, 80.0, 120.0))

    @Test fun normalPressurePasses() = assertNull(RecordValidator.validate(RecordType.BLOOD_PRESSURE, 120.0, 80.0))

    @Test fun bmiComputedFromHeight() {
        val bmi = UserSettings(heightCm = 170.0).bmi(72.25)!!
        assertEquals(25.0, bmi, 0.01)
    }

    @Test fun bmiNullWithoutHeight() = assertNull(UserSettings().bmi(70.0))
}
