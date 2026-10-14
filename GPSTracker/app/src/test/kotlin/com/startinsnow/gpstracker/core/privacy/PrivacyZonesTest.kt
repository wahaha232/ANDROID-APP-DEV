package com.startinsnow.gpstracker.core.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyZonesTest {
    // 1 度緯度約 111 km；沿緯度方向每個點相隔約 11.1 m
    private data class P(val lat: Double, val lon: Double)
    private val home = PrivacyZone(25.0, 121.5, 200)
    private fun route(n: Int) = List(n) { P(25.0 + it * 0.0001, 121.5) }

    @Test
    fun distance_isRoughlyRight() {
        assertEquals(111_195.0, PrivacyZones.distanceMeters(25.0, 121.5, 26.0, 121.5), 300.0)
        assertEquals(0.0, PrivacyZones.distanceMeters(25.0, 121.5, 25.0, 121.5), 1e-6)
    }

    @Test
    fun trimsTheStartAndEndInsideTheZone_butKeepsTheMiddle() {
        // 0..17 約在 0~190 m 內；第 20 點起離開；再走遠後回到家附近（結尾也在區域內）
        val out = route(40) + route(40).reversed() // 去程走遠，回程回到家
        val trimmed = PrivacyZones.trimEnds(out, listOf(home), { it.lat }, { it.lon })
        assertTrue(trimmed.size < out.size)
        assertFalse(PrivacyZones.isInside(trimmed.first().lat, trimmed.first().lon, listOf(home)))
        assertFalse(PrivacyZones.isInside(trimmed.last().lat, trimmed.last().lon, listOf(home)))
        // 中間（最遠點）一定保留
        assertTrue(trimmed.contains(P(25.0 + 39 * 0.0001, 121.5)))
    }

    @Test
    fun routeThroughTheZoneInTheMiddleIsNotTouched() {
        val pts = listOf(P(25.01, 121.5), P(25.0, 121.5), P(25.0005, 121.5), P(25.01, 121.5))
        assertEquals(pts, PrivacyZones.trimEnds(pts, listOf(home), { it.lat }, { it.lon }))
    }

    @Test
    fun everythingInsideTheZone_leavesNothing_andNoZonesLeavesEverything() {
        val inside = List(5) { P(25.0 + it * 0.00001, 121.5) }
        assertTrue(PrivacyZones.trimEnds(inside, listOf(home), { it.lat }, { it.lon }).isEmpty())
        val r = route(10)
        assertEquals(r, PrivacyZones.trimEnds(r, emptyList(), { it.lat }, { it.lon }))
        assertTrue(PrivacyZones.trimEnds(emptyList<P>(), listOf(home), { it.lat }, { it.lon }).isEmpty())
    }

    @Test
    fun multipleZones_anyOfThemTrims() {
        val work = PrivacyZone(25.05, 121.5, 300)
        val pts = listOf(P(25.05, 121.5), P(25.0, 121.5), P(24.99, 121.5), P(25.0, 121.5001))
        val t = PrivacyZones.trimEnds(pts, listOf(home, work), { it.lat }, { it.lon })
        assertEquals(listOf(P(24.99, 121.5)), t) // 開頭在公司、結尾在家，只剩中間離開兩區的點
    }

    @Test
    fun addRemoveAndLimits() {
        var z = emptyList<PrivacyZone>()
        repeat(PrivacyZones.MAX_ZONES + 2) { z = PrivacyZones.add(z, PrivacyZone(25.0 + it, 121.5, 200)) }
        assertEquals(PrivacyZones.MAX_ZONES, z.size)
        assertEquals(PrivacyZones.MAX_ZONES - 1, PrivacyZones.remove(z, 0).size)
        assertEquals(z, PrivacyZones.remove(z, 99))
        assertEquals(PrivacyZones.MIN_RADIUS, PrivacyZones.add(emptyList(), PrivacyZone(25.0, 121.5, 1)).first().radiusMeters)
        assertEquals(PrivacyZones.MAX_RADIUS, PrivacyZones.clampRadius(99_999))
    }

    @Test
    fun json_roundTrips_andBadInputIsHarmless() {
        val zones = listOf(PrivacyZone(25.033, 121.565, 200), PrivacyZone(24.1, 120.6, 500))
        assertEquals(zones, PrivacyZones.fromJson(PrivacyZones.toJson(zones)))
        assertTrue(PrivacyZones.fromJson(null).isEmpty())
        assertTrue(PrivacyZones.fromJson("").isEmpty())
        assertTrue(PrivacyZones.fromJson("not json").isEmpty())
        assertTrue(PrivacyZones.fromJson("[{\"lat\":999,\"lon\":0,\"r\":100}]").isEmpty())
        assertEquals(1, PrivacyZones.fromJson("[{\"lat\":25,\"lon\":121,\"r\":5},{\"x\":1}]").size)
        assertEquals(PrivacyZones.MIN_RADIUS, PrivacyZones.fromJson("[{\"lat\":25,\"lon\":121,\"r\":5}]").first().radiusMeters)
    }
}
