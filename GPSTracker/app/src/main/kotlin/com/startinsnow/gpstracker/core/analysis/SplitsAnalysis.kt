package com.startinsnow.gpstracker.core.analysis

import com.startinsnow.gpstracker.core.geo.GeoMath

/** 每公里分段中的一段。最後不滿 1 公里的餘段 partial = true（不參與最快／最慢比較）。 */
data class Split(val index: Int, val distanceMeters: Double, val seconds: Double, val partial: Boolean) {
    val avgKmh: Double get() = if (seconds > 0) (distanceMeters / 1000.0) / (seconds / 3600.0) else 0.0
    val paceSecPerKm: Double get() = if (distanceMeters > 0) seconds / (distanceMeters / 1000.0) else 0.0
}

/**
 * 時間分析：經過時間 ＞ 活動時間（扣掉暫停）＞ 移動時間（再扣掉停等）。
 * pausedSec = 經過 − 活動；stoppedSec = 活動 − 移動（沒有暫停、但位置沒動的時間，例如等紅燈）。
 */
data class TimeBreakdown(val elapsedSec: Double, val activeSec: Double, val movingSec: Double, val pausedSec: Double, val stoppedSec: Double)

/**
 * 每公里分段與移動時間。純邏輯，規則與網站（gt-core.js）一致：
 * - 相鄰兩點間隔超過 [MAX_SPLIT_GAP_S] 秒視為暫停或 GPS 遺失：該段的距離與時間都不計入分段（避免把
 *   搭捷運、暫停等整段跳躍算成不可能的速度）；所以各分段的距離加總可能略少於整趟距離。
 * - 移動時間：相鄰點間隔 ≤ [MAX_MOVING_GAP_S] 秒且速度 ≥ [STOP_SPEED_MPS] 的時間總和。
 */
object SplitsAnalysis {
    const val MAX_MOVING_GAP_S = 30.0
    const val MAX_SPLIT_GAP_S = 120.0
    const val STOP_SPEED_MPS = 0.6
    const val MIN_PARTIAL_M = 100.0

    fun splits(points: List<AnalysisPoint>): List<Split> {
        val out = mutableListOf<Split>()
        var cumDist = 0.0
        var cumTime = 0.0
        var lastBoundaryTime = 0.0
        var nextKm = 1
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val dt = (b.timestampMs - a.timestampMs) / 1000.0
            if (dt <= 0.0 || dt > MAX_SPLIT_GAP_S) continue
            var remD = GeoMath.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            var remT = dt
            var target = nextKm * 1000.0
            while (remD > 0.0 && cumDist + remD >= target - 1e-9) {
                val needD = target - cumDist
                val usedT = remT * (needD / remD)
                cumTime += usedT
                cumDist = target
                out.add(Split(nextKm, 1000.0, cumTime - lastBoundaryTime, false))
                lastBoundaryTime = cumTime
                remD -= needD
                remT -= usedT
                nextKm++
                target = nextKm * 1000.0
            }
            cumDist += remD
            cumTime += remT
        }
        val restD = cumDist - (nextKm - 1) * 1000.0
        if (restD >= MIN_PARTIAL_M) out.add(Split(nextKm, restD, cumTime - lastBoundaryTime, true))
        return out
    }

    /** 完整公里中最快與最慢的一段；完整公里少於 3 個時不比較（資料太少沒有意義）。 */
    fun fastestAndSlowest(splits: List<Split>): Pair<Split, Split>? {
        val full = splits.filter { !it.partial && it.seconds > 0 }
        if (full.size < 3) return null
        return full.minBy { it.seconds } to full.maxBy { it.seconds }
    }

    fun movingSeconds(points: List<AnalysisPoint>): Double {
        var sum = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val dt = (b.timestampMs - a.timestampMs) / 1000.0
            if (dt <= 0.0 || dt > MAX_MOVING_GAP_S) continue
            val d = GeoMath.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            if (d / dt >= STOP_SPEED_MPS) sum += dt
        }
        return sum
    }

    fun breakdown(startMs: Long, endMs: Long?, durationMs: Long, movingSec: Double): TimeBreakdown {
        val elapsed = (((endMs ?: startMs) - startMs) / 1000.0).coerceAtLeast(0.0)
        val active = (durationMs / 1000.0).coerceAtLeast(0.0)
        val moving = movingSec.coerceIn(0.0, active)
        return TimeBreakdown(
            elapsedSec = maxOf(elapsed, active), activeSec = active, movingSec = moving,
            pausedSec = (elapsed - active).coerceAtLeast(0.0), stoppedSec = (active - moving).coerceAtLeast(0.0)
        )
    }
}
