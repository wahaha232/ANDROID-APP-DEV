package com.startinsnow.gpstracker.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitsAnalysisTest {
    // 沿緯度方向、每秒前進 [metersPerSec] 公尺（1 度緯度 ≈ 111195 m）
    private fun walk(seconds: Int, metersPerSec: Double, startMs: Long = 0L, startLat: Double = 25.0): List<AnalysisPoint> =
        List(seconds + 1) { s ->
            AnalysisPoint(startLat + s * metersPerSec / 111_195.0, 121.5, null, null, startMs + s * 1000L)
        }

    @Test
    fun constantSpeed_givesEqualSplits_andAPartialRemainder() {
        // 4 m/s × 650 s = 2600 m → 2 個完整公里（各 250 s）＋ 600 m 的餘段
        val s = SplitsAnalysis.splits(walk(650, 4.0))
        assertEquals(3, s.size)
        assertEquals(250.0, s[0].seconds, 1.0)
        assertEquals(250.0, s[1].seconds, 1.0)
        assertEquals(1000.0, s[0].distanceMeters, 1e-9)
        assertTrue(s[2].partial)
        assertEquals(600.0, s[2].distanceMeters, 5.0)
        assertEquals(14.4, s[0].avgKmh, 0.1)
        assertEquals(250.0, s[0].paceSecPerKm, 1.0)
    }

    @Test
    fun aShortRemainderIsDropped_andTooFewPointsGiveNothing() {
        assertTrue(SplitsAnalysis.splits(walk(260, 4.0)).none { it.partial }) // 1040 m：餘 40 m < 100 m
        assertTrue(SplitsAnalysis.splits(emptyList()).isEmpty())
        assertTrue(SplitsAnalysis.splits(walk(1, 4.0)).isEmpty())
    }

    @Test
    fun aLongGap_isNotCounted_soItCannotCreateAnImpossibleSplit() {
        // 前半段 500 m（125 s）→ 暫停／GPS 遺失 10 分鐘（跳 3 km）→ 後半段 600 m（150 s）
        val a = walk(125, 4.0)
        val jumpLat = a.last().latitude + 3000.0 / 111_195.0
        val b = List(151) { s -> AnalysisPoint(jumpLat + s * 4.0 / 111_195.0, 121.5, null, null, 125_000L + 600_000L + s * 1000L) }
        val s = SplitsAnalysis.splits(a + b)
        assertEquals(1, s.count { !it.partial })
        assertEquals(250.0, s.first { !it.partial }.seconds, 2.0) // 1000 m 都是以 4 m/s 走的
        assertTrue("跳躍的 3 km 沒有被算進去", s.sumOf { it.distanceMeters } < 1200.0)
    }

    @Test
    fun fastestAndSlowest_needAtLeastThreeFullKilometres() {
        val fast = walk(125, 8.0)                       // 1000 m：125 s
        val mid = walk(250, 4.0, startMs = 125_000L, startLat = fast.last().latitude)
        val slow = walk(505, 2.0, startMs = 375_000L, startLat = mid.last().latitude) // 多走 10 m，避免浮點誤差讓第 3 公里差一點點沒走完
        val s = SplitsAnalysis.splits(fast + mid.drop(1) + slow.drop(1))
        assertEquals(3, s.count { !it.partial })
        val (f, sl) = SplitsAnalysis.fastestAndSlowest(s)!!
        assertEquals(1, f.index); assertEquals(3, sl.index)
        assertNull(SplitsAnalysis.fastestAndSlowest(s.take(2)))
        assertNotNull(f)
    }

    @Test
    fun movingTime_ignoresStops_andLongGaps() {
        // 100 s 以 3 m/s 前進 → 站著 60 s（位置不動）→ 再 40 s 前進
        val a = walk(100, 3.0)
        val still = List(60) { s -> AnalysisPoint(a.last().latitude, 121.5, null, null, 100_000L + (s + 1) * 1000L) }
        val c = List(41) { s -> AnalysisPoint(a.last().latitude + s * 3.0 / 111_195.0, 121.5, null, null, 160_000L + s * 1000L) }
        val moving = SplitsAnalysis.movingSeconds(a + still + c)
        assertEquals(140.0, moving, 2.0)
        // 相鄰點間隔超過 30 秒的那一段不算移動
        assertEquals(0.0, SplitsAnalysis.movingSeconds(listOf(a[0], a[1].copy(timestampMs = 100_000L))), 1e-9)
    }

    @Test
    fun breakdown_separatesPausedFromStopped() {
        val b = SplitsAnalysis.breakdown(startMs = 0L, endMs = 1_000_000L, durationMs = 800_000L, movingSec = 600.0)
        assertEquals(1000.0, b.elapsedSec, 1e-9)
        assertEquals(800.0, b.activeSec, 1e-9)
        assertEquals(200.0, b.pausedSec, 1e-9)
        assertEquals(600.0, b.movingSec, 1e-9)
        assertEquals(200.0, b.stoppedSec, 1e-9)
        // 不會出現負數或移動時間大於活動時間
        val odd = SplitsAnalysis.breakdown(0L, null, 100_000L, 500.0)
        assertTrue(odd.pausedSec >= 0 && odd.stoppedSec >= 0 && odd.movingSec <= odd.activeSec && odd.elapsedSec >= odd.activeSec)
    }
}
