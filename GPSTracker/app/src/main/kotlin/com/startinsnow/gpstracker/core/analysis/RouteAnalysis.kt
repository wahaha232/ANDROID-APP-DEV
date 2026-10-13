package com.startinsnow.gpstracker.core.analysis

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** 分析用的軌跡點（只保留需要的欄位，與資料庫實體分離，方便單元測試）。 */
data class AnalysisPoint(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val speedMps: Double?,
    val timestampMs: Long
)

enum class ColorMode { NONE, SPEED, ALTITUDE }

/** 同色的連續線段；coordinates 為 (緯度, 經度)。 */
data class ColoredSegment(val color: String, val coordinates: List<Pair<Double, Double>>)

data class ColorResult(val segments: List<ColoredSegment>, val low: Double, val high: Double)

enum class HighlightKind { MAX_ALTITUDE, BIGGEST_CLIMB, MAX_SPEED }

data class RouteHighlight(
    val kind: HighlightKind,
    val index: Int,
    val value: Double,
    val latitude: Double,
    val longitude: Double,
    val elapsedMs: Long
)

/**
 * 路線上色（依速度／海拔）與自動亮點。純邏輯，規則與網站（gt-core.js）一致：
 * 速度超過 [SPEED_TRUST_MAX_KMH] 視為 GPS 異常、不參與；海拔 0 或缺值以前一個有效值補；
 * 色階範圍取第 5～95 百分位，單一尖峰不會把整條路線洗成同一個顏色；資料不足就回傳 null／空清單，不捏造。
 */
object RouteAnalysis {
    const val SPEED_TRUST_MAX_KMH = 400.0

    private val SPEED_RAMP = listOf("#38bdf8", "#34d399", "#fbbf24", "#f87171")
    private val ALTITUDE_RAMP = listOf("#3b82f6", "#22d3ee", "#a3e635", "#fde047", "#fb923c")

    fun movingAverage(values: DoubleArray, window: Int): DoubleArray {
        val half = window / 2
        return DoubleArray(values.size) { i ->
            var sum = 0.0
            var n = 0
            for (j in maxOf(0, i - half)..min(values.size - 1, i + half)) { sum += values[j]; n++ }
            sum / n
        }
    }

    /** 逐點速度（km/h）；沒有任何可用速度回傳 null。 */
    fun speedSeriesKmh(points: List<AnalysisPoint>): DoubleArray? {
        if (points.size < 2) return null
        val v = DoubleArray(points.size) { i ->
            val kmh = (points[i].speedMps ?: 0.0) * 3.6
            if (kmh.isFinite() && kmh <= SPEED_TRUST_MAX_KMH) kmh else 0.0
        }
        return if (v.any { it > 0.5 }) movingAverage(v, 5) else null
    }

    /** 逐點海拔（m）；0／缺值以前一個有效值補，至少要有 2 個有效且有高低差，否則回傳 null。 */
    fun altitudeSeries(points: List<AnalysisPoint>): DoubleArray? {
        if (points.size < 2) return null
        val raw = points.map { p -> p.altitudeMeters?.takeIf { it.isFinite() && it != 0.0 } }
        val valid = raw.filterNotNull()
        if (valid.size < 2 || valid.max() <= valid.min()) return null
        var last = valid.first()
        val filled = DoubleArray(points.size)
        raw.forEachIndexed { i, a -> if (a != null) last = a; filled[i] = last }
        return movingAverage(filled, 5)
    }

    fun series(points: List<AnalysisPoint>, mode: ColorMode): DoubleArray? = when (mode) {
        ColorMode.SPEED -> speedSeriesKmh(points)
        ColorMode.ALTITUDE -> altitudeSeries(points)
        ColorMode.NONE -> null
    }

    fun rampColor(mode: ColorMode, t: Double): String {
        val stops = if (mode == ColorMode.ALTITUDE) ALTITUDE_RAMP else SPEED_RAMP
        val x = t.coerceIn(0.0, 1.0) * (stops.size - 1)
        val i = min(stops.size - 2, floor(x).toInt())
        val f = x - i
        val a = rgb(stops[i])
        val b = rgb(stops[i + 1])
        val c = IntArray(3) { k -> (a[k] + (b[k] - a[k]) * f).roundToInt() }
        return "#%02x%02x%02x".format(c[0], c[1], c[2])
    }

    private fun rgb(hex: String) = intArrayOf(hex.substring(1, 3).toInt(16), hex.substring(3, 5).toInt(16), hex.substring(5, 7).toInt(16))

    /** 色階範圍：第 5～95 百分位；數值幾乎相同時退回最小～最大，仍相同就加 1 避免除以 0。 */
    fun range(values: DoubleArray): Pair<Double, Double> {
        val s = values.sorted()
        var lo = s[floor((s.size - 1) * 0.05).toInt()]
        var hi = s[ceil((s.size - 1) * 0.95).toInt()]
        if (!(hi - lo > 1e-6)) { lo = s.first(); hi = s.last() }
        if (!(hi - lo > 1e-6)) hi = lo + 1.0
        return lo to hi
    }

    /** 點太多時等距抽樣（保留首尾），避免地圖上產生過多線段。 */
    fun downsample(points: List<AnalysisPoint>, max: Int): List<AnalysisPoint> {
        if (points.size <= max || max < 2) return points
        val step = (points.size - 1).toDouble() / (max - 1)
        return List(max) { i -> points[(i * step).roundToInt().coerceAtMost(points.size - 1)] }
    }

    /** 把路線切成同色的連續線段（最多 [buckets] 種顏色）。values 與 points 等長。 */
    fun colorSegments(points: List<AnalysisPoint>, values: DoubleArray, mode: ColorMode, buckets: Int = 16): ColorResult {
        require(points.size == values.size) { "points 與 values 長度必須相同" }
        val (lo, hi) = range(values)
        val segments = mutableListOf<ColoredSegment>()
        var currentBucket = -1
        var current: MutableList<Pair<Double, Double>>? = null
        for (j in 1 until points.size) {
            val t = (((values[j - 1] + values[j]) / 2 - lo) / (hi - lo)).coerceIn(0.0, 1.0)
            val bucket = (t * (buckets - 1)).roundToInt()
            val p1 = points[j]
            if (current != null && bucket == currentBucket) {
                current.add(p1.latitude to p1.longitude)
            } else {
                currentBucket = bucket
                val p0 = points[j - 1]
                val list = mutableListOf(p0.latitude to p0.longitude, p1.latitude to p1.longitude)
                current = list
                segments.add(ColoredSegment(rampColor(mode, bucket.toDouble() / (buckets - 1)), list))
            }
        }
        return ColorResult(segments, lo, hi)
    }

    /** 自動亮點：最高點、最大連續爬升（≥ 10 m）、最快速度（≥ 3 km/h）。資料不足的項目不列出。 */
    fun highlights(points: List<AnalysisPoint>): List<RouteHighlight> {
        if (points.size < 3) return emptyList()
        val out = mutableListOf<RouteHighlight>()
        val t0 = points.first().timestampMs
        fun add(kind: HighlightKind, idx: Int, value: Double) {
            val p = points[idx]
            out.add(RouteHighlight(kind, idx, value, p.latitude, p.longitude, p.timestampMs - t0))
        }
        val alt = altitudeSeries(points)
        if (alt != null) {
            var top = 0
            for (i in 1 until alt.size) if (alt[i] > alt[top]) top = i
            add(HighlightKind.MAX_ALTITUDE, top, alt[top])
            var minIdx = 0
            var best = 0.0
            var bestEnd = 0
            for (i in 1 until alt.size) {
                if (alt[i] < alt[minIdx]) minIdx = i
                if (alt[i] - alt[minIdx] > best) { best = alt[i] - alt[minIdx]; bestEnd = i }
            }
            if (best >= 10.0) add(HighlightKind.BIGGEST_CLIMB, bestEnd, best)
        }
        val spd = speedSeriesKmh(points)
        if (spd != null) {
            var fast = 0
            for (i in 1 until spd.size) if (spd[i] > spd[fast]) fast = i
            if (spd[fast] >= 3.0) add(HighlightKind.MAX_SPEED, fast, spd[fast])
        }
        return out.sortedBy { it.index }
    }
}
