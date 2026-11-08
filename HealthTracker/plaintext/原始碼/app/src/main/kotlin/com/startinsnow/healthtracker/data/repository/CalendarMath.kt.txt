package com.startinsnow.healthtracker.data.repository

import com.startinsnow.healthtracker.data.db.HealthRecord
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** 日曆熱度圖與時段點圖用的純計算。只算筆數與位置，不評斷數值好壞。 */
object CalendarMath {
    /** 月曆格子（週日起算）：前面補 null 對齊星期，之後是當月每一天。 */
    fun monthGrid(month: YearMonth): List<LocalDate?> {
        val first = month.atDay(1)
        val lead = first.dayOfWeek.value % 7 // 週日=0、週一=1…週六=6
        return List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    }

    fun countsByDay(records: List<HealthRecord>, zone: ZoneId): Map<LocalDate, Int> =
        records.groupingBy { Instant.ofEpochMilli(it.measuredAt).atZone(zone).toLocalDate() }.eachCount()

    /** 顏色深淺：0 筆=0、1 筆=1、2 筆=2、3 筆以上=3。 */
    fun level(count: Int): Int = count.coerceIn(0, 3)

    /** 時段點圖的一個點：row 0 = 今天、往下是更早的日子；fraction = 當天第幾分鐘 / 1440。 */
    data class DayDot(val row: Int, val fraction: Float, val record: HealthRecord)

    fun dayDots(records: List<HealthRecord>, rows: Int, today: LocalDate, zone: ZoneId): List<DayDot> =
        records.mapNotNull { r ->
            val t = Instant.ofEpochMilli(r.measuredAt).atZone(zone)
            val row = (today.toEpochDay() - t.toLocalDate().toEpochDay()).toInt()
            if (row !in 0 until rows) null
            else DayDot(row, (t.hour * 60 + t.minute) / 1440f, r)
        }
}
