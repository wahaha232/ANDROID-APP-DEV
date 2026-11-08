package com.startinsnow.healthtracker.data.repository

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** 測量時間的換算（日期選擇器回傳的是 UTC 午夜毫秒，要換回當地日期再接上原本的時分）。 */
object MeasureTime {
    /** 允許比現在晚一點點（手機時鐘與血糖機時鐘的誤差），超過就視為輸入錯誤。 */
    const val FUTURE_TOLERANCE_MS = 5 * 60_000L

    fun isFuture(measuredAt: Long, now: Long): Boolean = measuredAt > now + FUTURE_TOLERANCE_MS

    /** 當地日期的 UTC 午夜毫秒，給 Material3 DatePicker 當初始選取值。 */
    fun utcMidnightOfLocalDate(measuredAt: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(measuredAt).atZone(zone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** 換成選到的日期（DatePicker 回傳 UTC 午夜毫秒），保留原本的時、分。 */
    fun withDate(measuredAt: Long, pickedUtcMidnightMillis: Long, zone: ZoneId): Long {
        val picked: LocalDate = Instant.ofEpochMilli(pickedUtcMidnightMillis).atZone(ZoneOffset.UTC).toLocalDate()
        val time = Instant.ofEpochMilli(measuredAt).atZone(zone).toLocalTime().withSecond(0).withNano(0)
        return picked.atTime(time).atZone(zone).toInstant().toEpochMilli()
    }

    /** 換成選到的時、分，保留原本的日期。 */
    fun withTime(measuredAt: Long, hour: Int, minute: Int, zone: ZoneId): Long {
        val date = Instant.ofEpochMilli(measuredAt).atZone(zone).toLocalDate()
        return date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    }
}
