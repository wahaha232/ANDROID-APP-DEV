package com.startinsnow.gpstracker.core.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 對應「長時間 Recording / 大量 GPS Points 不得造成 UI Freeze」的地圖顯示縮減邏輯測試。 */
class RouteSimplifierTest {

    private fun route(count: Int): List<Pair<Double, Double>> =
        (0 until count).map { i -> 25.0 + i * 0.0001 to 121.0 + i * 0.0001 }

    @Test
    fun `route under the limit is returned unchanged`() {
        val coords = route(100)
        assertEquals(coords, RouteSimplifier.simplify(coords, maxPoints = 4000))
    }

    @Test
    fun `route over the limit is reduced to at most the limit`() {
        val simplified = RouteSimplifier.simplify(route(10_000), maxPoints = 1000)
        assertTrue("size=${simplified.size}", simplified.size <= 1000)
    }

    @Test
    fun `first and last point are always preserved`() {
        val coords = route(50_000)
        val simplified = RouteSimplifier.simplify(coords, maxPoints = 2000)
        assertEquals(coords.first(), simplified.first())
        assertEquals(coords.last(), simplified.last())
    }

    @Test
    fun `simplified points keep the original order`() {
        val coords = route(5_000)
        val simplified = RouteSimplifier.simplify(coords, maxPoints = 500)
        val indices = simplified.map { coords.indexOf(it) }
        assertTrue(indices.all { it >= 0 })
        assertEquals(indices.sorted(), indices)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `maxPoints below two is rejected`() {
        RouteSimplifier.simplify(route(10), maxPoints = 1)
    }
}
