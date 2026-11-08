package com.startinsnow.healthtracker.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

class SyncSchedulerTest {
    private val taipei = ZoneId.of("Asia/Taipei")
    private fun at(h: Int, m: Int = 0, s: Int = 0) = ZonedDateTime.of(2026, 10, 19, h, m, s, 0, taipei)

    @Test fun beforeEightPmWaitsUntilToday() = assertEquals(Duration.ofHours(2), SyncScheduler.delayUntilNext(20, at(18)))

    @Test fun afterEightPmWaitsUntilTomorrow() = assertEquals(Duration.ofHours(23), SyncScheduler.delayUntilNext(20, at(21)))

    @Test fun exactlyEightPmSchedulesTomorrow() = assertEquals(Duration.ofHours(24), SyncScheduler.delayUntilNext(20, at(20)))

    @Test fun secondsAreAccountedFor() = assertEquals(Duration.ofMinutes(29).plusSeconds(30), SyncScheduler.delayUntilNext(20, at(19, 30, 30)))

    @Test fun crossesMonthBoundary() {
        val now = ZonedDateTime.of(2026, 10, 31, 22, 0, 0, 0, taipei)
        assertEquals(Duration.ofHours(22), SyncScheduler.delayUntilNext(20, now))
    }

    @Test fun dayLightSavingShiftIsRespected() {
        val ny = ZoneId.of("America/New_York") // 2026-11-01 回撥一小時：當天 18:00 到 20:00 實際 3 小時（含重複的 1 點不影響），此處驗證用牆上時間計算
        val now = ZonedDateTime.of(2026, 11, 1, 18, 0, 0, 0, ny)
        assertEquals(Duration.ofHours(2), SyncScheduler.delayUntilNext(20, now))
    }
}
