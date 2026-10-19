package com.startinsnow.healthtracker.data.repository

import com.startinsnow.healthtracker.data.db.HealthRecord
import com.startinsnow.healthtracker.data.db.RecordType
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** 總覽畫面用的純計算：最新值、近 N 天資料、統計、圖表刻度、「多久以前」。 */
object OverviewMath {
    private const val DAY_MS = 86_400_000L

    data class Scale(val min: Double, val max: Double, val ticks: List<Double>)
    data class Stats(val count: Int, val avg: Double, val min: Double, val max: Double)

    fun latest(records: List<HealthRecord>, type: RecordType): HealthRecord? =
        records.filter { it.type == type && it.deletedAt == null }.maxByOrNull { it.measuredAt }

    /** 近 days 天（含今天）、依時間由舊到新。不補任何資料；未來太遠的時間（輸入錯誤）不納入。 */
    fun window(records: List<HealthRecord>, type: RecordType, days: Int, now: Long): List<HealthRecord> {
        val from = now - days * DAY_MS
        return records
            .filter { it.type == type && it.deletedAt == null && it.measuredAt in from..(now + MeasureTime.FUTURE_TOLERANCE_MS) }
            .sortedBy { it.measuredAt }
    }

    fun stats(list: List<HealthRecord>): Stats? {
        if (list.isEmpty()) return null
        val v = list.map { it.value }
        return Stats(list.size, v.average(), v.min(), v.max())
    }

    /** 1／2／5 × 10^n 的漂亮刻度（與網站圖表同一套規則）。 */
    fun niceScale(min: Double, max: Double, count: Int = 5): Scale {
        if (!min.isFinite() || !max.isFinite()) return Scale(0.0, 1.0, listOf(0.0, 1.0))
        var lo = min
        var hi = max
        if (lo == hi) { lo -= 1; hi += 1 }
        val rough = (hi - lo) / maxOf(1, count - 1)
        val pow = 10.0.pow(floor(log10(rough)))
        val frac = rough / pow
        val step = (if (frac <= 1) 1.0 else if (frac <= 2) 2.0 else if (frac <= 5) 5.0 else 10.0) * pow
        val start = floor(lo / step) * step
        val end = ceil(hi / step) * step
        val ticks = ArrayList<Double>()
        var t = start
        while (t <= end + step / 2) {
            ticks += Math.round(t * 1e6) / 1e6
            t += step
        }
        return Scale(ticks.first(), ticks.last(), ticks)
    }

    fun relativeAge(measuredAt: Long, now: Long): String {
        val diff = now - measuredAt
        return when {
            diff < 60_000 -> "剛剛"
            diff < 3_600_000 -> "${diff / 60_000} 分鐘前"
            diff < DAY_MS -> "${diff / 3_600_000} 小時前"
            else -> "${diff / DAY_MS} 天前"
        }
    }

    /** 兩個數值是否「差不多」（測試用的小工具，避免浮點誤差）。 */
    internal fun close(a: Double, b: Double) = abs(a - b) < 1e-9
}
