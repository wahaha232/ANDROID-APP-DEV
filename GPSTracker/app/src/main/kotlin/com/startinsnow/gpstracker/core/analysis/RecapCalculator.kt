package com.startinsnow.gpstracker.core.analysis

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 回顧用的活動摘要（只保留需要的欄位）。 */
data class TrackSummary(
    val trackId: String,
    val startTimeMs: Long,
    val distanceMeters: Double,
    val durationMs: Long,
    val ascentMeters: Double,
    val steps: Long,
    val maxSpeedMps: Double,
    val mode: String
)

data class PeriodTotals(val count: Int, val km: Double, val durationMs: Long, val ascentMeters: Double, val steps: Long, val activeDays: Int)

/** label：整年時是 1～12（月），單月時是 1～31（日）。 */
data class RecapBucket(val label: Int, val km: Double, val count: Int)

data class ModeShare(val mode: String, val km: Double, val count: Int, val percent: Double)

data class DayTotal(val date: LocalDate, val km: Double, val count: Int)

data class RecapResult(
    val year: Int,
    /** 0 = 整年；1～12 = 該月。 */
    val month: Int,
    val totals: PeriodTotals,
    val previousYear: Int,
    val previousMonth: Int,
    val previous: PeriodTotals,
    val buckets: List<RecapBucket>,
    val modes: List<ModeShare>,
    val longest: TrackSummary?,
    val fastest: TrackSummary?,
    val busiestDay: DayTotal?,
    val streakDays: Int,
    val top: List<TrackSummary>
)

/**
 * 年度／月度回顧的計算。純邏輯；規則與網站（gt-recap.js）一致：
 * 「有效活動」＝距離與時間皆 > 0 且平均速度不超過 1200 km/h（與網站 SOLAR_CORE.validTracks 相同的異常門檻）；
 * 最快速度排除超過 400 km/h 的 GPS 異常值；上一期沒有活動時由呼叫端自行決定怎麼顯示（不在這裡算百分比）。
 */
object RecapCalculator {
    const val ANOMALY_MAX_KMH = 1200.0
    const val SPEED_TRUST_MAX_KMH = 400.0

    fun isValid(t: TrackSummary): Boolean {
        if (!(t.distanceMeters > 0.0) || t.durationMs <= 0L) return false
        val kmh = (t.distanceMeters / 1000.0) / (t.durationMs / 3_600_000.0)
        return kmh <= ANOMALY_MAX_KMH
    }

    private fun dateOf(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    fun years(tracks: List<TrackSummary>, zone: ZoneId = ZoneId.systemDefault()): List<Int> =
        tracks.filter(::isValid).map { dateOf(it.startTimeMs, zone).year }.distinct().sortedDescending()

    fun previousPeriod(year: Int, month: Int): Pair<Int, Int> = when {
        month == 0 -> (year - 1) to 0
        month == 1 -> (year - 1) to 12
        else -> year to (month - 1)
    }

    private fun inPeriod(t: TrackSummary, year: Int, month: Int, zone: ZoneId): Boolean {
        val d = dateOf(t.startTimeMs, zone)
        return d.year == year && (month == 0 || d.monthValue == month)
    }

    private fun totalsOf(list: List<TrackSummary>, zone: ZoneId): PeriodTotals = PeriodTotals(
        count = list.size,
        km = list.sumOf { it.distanceMeters } / 1000.0,
        durationMs = list.sumOf { it.durationMs },
        ascentMeters = list.sumOf { it.ascentMeters },
        steps = list.sumOf { it.steps },
        activeDays = list.map { dateOf(it.startTimeMs, zone) }.distinct().size
    )

    fun longestStreak(list: List<TrackSummary>, zone: ZoneId = ZoneId.systemDefault()): Int {
        val days = list.map { dateOf(it.startTimeMs, zone) }.distinct().sorted()
        var best = 0
        var run = 0
        var prev: LocalDate? = null
        for (d in days) {
            run = if (prev != null && ChronoUnit.DAYS.between(prev, d) == 1L) run + 1 else 1
            prev = d
            if (run > best) best = run
        }
        return best
    }

    fun compute(all: List<TrackSummary>, year: Int, month: Int, zone: ZoneId = ZoneId.systemDefault()): RecapResult {
        val valid = all.filter(::isValid)
        val list = valid.filter { inPeriod(it, year, month, zone) }
        val (py, pm) = previousPeriod(year, month)
        val prevList = valid.filter { inPeriod(it, py, pm, zone) }

        val buckets: List<RecapBucket> = if (month == 0) {
            (1..12).map { m ->
                val inMonth = list.filter { dateOf(it.startTimeMs, zone).monthValue == m }
                RecapBucket(m, inMonth.sumOf { it.distanceMeters } / 1000.0, inMonth.size)
            }
        } else {
            val days = java.time.YearMonth.of(year, month).lengthOfMonth()
            (1..days).map { day ->
                val onDay = list.filter { dateOf(it.startTimeMs, zone).dayOfMonth == day }
                RecapBucket(day, onDay.sumOf { it.distanceMeters } / 1000.0, onDay.size)
            }
        }

        val totalKm = list.sumOf { it.distanceMeters } / 1000.0
        val modes = list.groupBy { it.mode }.map { (mode, items) ->
            val km = items.sumOf { it.distanceMeters } / 1000.0
            ModeShare(mode, km, items.size, if (totalKm > 0) km / totalKm * 100.0 else 0.0)
        }.sortedByDescending { it.km }

        val busiest = list.groupBy { dateOf(it.startTimeMs, zone) }
            .map { (date, items) -> DayTotal(date, items.sumOf { it.distanceMeters } / 1000.0, items.size) }
            .maxByOrNull { it.km }

        val fastest = list.filter { it.maxSpeedMps * 3.6 in 0.000001..SPEED_TRUST_MAX_KMH }.maxByOrNull { it.maxSpeedMps }

        return RecapResult(
            year = year, month = month, totals = totalsOf(list, zone),
            previousYear = py, previousMonth = pm, previous = totalsOf(prevList, zone),
            buckets = buckets, modes = modes,
            longest = list.maxByOrNull { it.distanceMeters }, fastest = fastest, busiestDay = busiest,
            streakDays = longestStreak(list, zone),
            top = list.sortedByDescending { it.distanceMeters }.take(5)
        )
    }
}
