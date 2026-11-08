package com.startinsnow.healthtracker

import com.startinsnow.healthtracker.data.db.HealthRecord
import com.startinsnow.healthtracker.data.db.RecordType
import com.startinsnow.healthtracker.reminder.ReminderLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderLogicTest {
    private val zone = ZoneId.of("Asia/Taipei")
    private val now = ZonedDateTime.of(2026, 10, 19, 9, 0, 0, 0, zone)

    private fun rec(type: RecordType, at: ZonedDateTime, deleted: Boolean = false) = HealthRecord(
        id = "x${at.toEpochSecond()}$type", type = type, value = 100.0, measuredAt = at.toInstant().toEpochMilli(),
        createdAt = 1, updatedAt = 1, deletedAt = if (deleted) 5L else null,
    )

    @Test fun laterTodayIsSameDay() = assertEquals(Duration.ofHours(1), ReminderLogic.delayUntilNext(10 * 60, now))

    @Test fun passedTimeGoesToTomorrow() = assertEquals(Duration.ofHours(23), ReminderLogic.delayUntilNext(8 * 60, now))

    @Test fun exactlyNowGoesToTomorrow() = assertEquals(Duration.ofHours(24), ReminderLogic.delayUntilNext(9 * 60, now))

    @Test fun notifiesWhenNothingRecordedToday() =
        assertTrue(ReminderLogic.shouldNotify(listOf(rec(RecordType.BLOOD_GLUCOSE, now.minusDays(1))), true, now))

    @Test fun skipsWhenGlucoseRecordedToday() =
        assertFalse(ReminderLogic.shouldNotify(listOf(rec(RecordType.BLOOD_GLUCOSE, now.minusHours(2))), true, now))

    @Test fun otherTypesOrDeletedDoNotCount() {
        val list = listOf(rec(RecordType.WEIGHT, now.minusHours(2)), rec(RecordType.BLOOD_GLUCOSE, now.minusHours(1), deleted = true))
        assertTrue(ReminderLogic.shouldNotify(list, true, now))
    }

    @Test fun alwaysNotifiesWhenSkipOff() =
        assertTrue(ReminderLogic.shouldNotify(listOf(rec(RecordType.BLOOD_GLUCOSE, now.minusHours(2))), false, now))

    @Test fun labelPadsZeros() = assertEquals("07:05", ReminderLogic.label(7 * 60 + 5))
}
