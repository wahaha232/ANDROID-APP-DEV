package com.startinsnow.gpstracker.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteAnalysisTest {

    private fun line(n: Int, f: (Int) -> Pair<Double?, Double?>): List<AnalysisPoint> = List(n) { i ->
        val (alt, spd) = f(i)
        AnalysisPoint(25.0 + i * 0.0002, 121.5 + i * 0.0005, alt, spd, 1_000_000L + i * 5_000L)
    }

    @Test
    fun speedSeries_isKmh_andIgnoresAbsurdSpikes() {
        val pts = line(40) { i -> null to (if (i == 20) 500.0 else 5.0) } // 500 m/s 是 GPS 異常
        val v = RouteAnalysis.speedSeriesKmh(pts)!!
        assertEquals(18.0, v[5], 1e-9)
        assertTrue("異常尖峰不會變成 1800 km/h", v.max() < 25.0)
    }

    @Test
    fun noUsableData_returnsNull_insteadOfInventingValues() {
        assertNull(RouteAnalysis.speedSeriesKmh(line(30) { null to 0.0 }))
        assertNull(RouteAnalysis.altitudeSeries(line(30) { 0.0 to 1.0 }))   // 海拔 0 視為缺值
        assertNull(RouteAnalysis.altitudeSeries(line(30) { null to 1.0 }))
        assertNull(RouteAnalysis.altitudeSeries(line(30) { 12.0 to 1.0 }))  // 沒有高低差
        assertNull(RouteAnalysis.speedSeriesKmh(emptyList()))
        assertNull(RouteAnalysis.series(line(10) { 5.0 to 2.0 }, ColorMode.NONE))
    }

    @Test
    fun rampColors_areValidHex_andEndpointsAreFixed() {
        for (mode in listOf(ColorMode.SPEED, ColorMode.ALTITUDE)) {
            var t = -0.5
            while (t <= 1.5) {
                assertTrue(RouteAnalysis.rampColor(mode, t).matches(Regex("#[0-9a-f]{6}")))
                t += 0.07
            }
        }
        assertEquals("#38bdf8", RouteAnalysis.rampColor(ColorMode.SPEED, 0.0))
        assertEquals("#f87171", RouteAnalysis.rampColor(ColorMode.SPEED, 1.0))
        assertEquals("#3b82f6", RouteAnalysis.rampColor(ColorMode.ALTITUDE, 0.0))
        assertEquals("#fb923c", RouteAnalysis.rampColor(ColorMode.ALTITUDE, 1.0))
    }

    @Test
    fun range_ignoresASingleSpike_andNeverDividesByZero() {
        val vals = DoubleArray(100) { 10.0 + (it % 10) }
        vals[50] = 1000.0
        assertTrue(RouteAnalysis.range(vals).second < 30.0)
        val flat = RouteAnalysis.range(doubleArrayOf(5.0, 5.0, 5.0, 5.0))
        assertTrue(flat.second > flat.first)
    }

    @Test
    fun colorSegments_mergesSameColor_andCoversEveryEdge() {
        val pts = line(10) { null to 1.0 }
        val values = doubleArrayOf(1.0, 1.0, 1.0, 1.0, 9.0, 9.0, 9.0, 9.0, 9.0, 9.0)
        val r = RouteAnalysis.colorSegments(pts, values, ColorMode.SPEED, 8)
        assertTrue(r.segments.size >= 2)
        assertTrue(r.segments.map { it.color }.toSet().size >= 2)
        assertEquals(pts.size - 1, r.segments.sumOf { it.coordinates.size - 1 })
        assertEquals(1.0, r.low, 1e-9)
        assertEquals(9.0, r.high, 1e-9)
    }

    @Test
    fun downsample_keepsEndpoints() {
        val pts = line(1000) { null to 1.0 }
        val d = RouteAnalysis.downsample(pts, 100)
        assertEquals(100, d.size)
        assertEquals(pts.first(), d.first())
        assertEquals(pts.last(), d.last())
        assertEquals(pts, RouteAnalysis.downsample(pts, 5000))
    }

    @Test
    fun highlights_findHighestPoint_biggestClimb_andFastestSpeed() {
        val pts = line(80) { i ->
            val alt = when {
                i <= 30 -> 20.0 + i * (40.0 / 30)
                i <= 60 -> 60.0 - (i - 30) * (50.0 / 30)
                else -> 10.0 + (i - 60) * 0.1
            }
            alt to (if (i == 45) 12.0 else 4.0)
        }
        val hl = RouteAnalysis.highlights(pts).associateBy { it.kind }
        assertNotNull(hl[HighlightKind.MAX_ALTITUDE])
        assertTrue(Math.abs(hl[HighlightKind.MAX_ALTITUDE]!!.index - 30) <= 3)
        assertTrue(hl[HighlightKind.BIGGEST_CLIMB]!!.value >= 30.0)
        assertTrue(Math.abs(hl[HighlightKind.MAX_SPEED]!!.index - 45) <= 2)
        val sorted = RouteAnalysis.highlights(pts).map { it.index }
        assertEquals(sorted.sorted(), sorted)
    }

    @Test
    fun highlights_omitWhatTheDataCannotSupport() {
        assertTrue(RouteAnalysis.highlights(emptyList()).isEmpty())
        assertTrue(RouteAnalysis.highlights(line(2) { 5.0 to 5.0 }).isEmpty())
        val noAltitude = RouteAnalysis.highlights(line(60) { null to 1.2 })
        assertFalse(noAltitude.any { it.kind == HighlightKind.MAX_ALTITUDE || it.kind == HighlightKind.BIGGEST_CLIMB })
        val gentle = RouteAnalysis.highlights(line(60) { i -> (30.0 + i % 3) to 4.0 })
        assertFalse("起伏不到 10 m 不算爬升", gentle.any { it.kind == HighlightKind.BIGGEST_CLIMB })
    }
}
