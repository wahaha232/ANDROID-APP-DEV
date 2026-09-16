package com.startinsnow.gpstracker.core.geo

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoMathTest {

    @Test
    fun `distance between identical points is zero`() {
        val d = GeoMath.distanceMeters(25.0330, 121.5654, 25.0330, 121.5654)
        assertEquals(0.0, d, 0.001)
    }

    @Test
    fun `distance between two nearby Taipei coordinates is roughly correct`() {
        // ~0.0358 deg lat + ~0.0123 deg lon apart near 25N, haversine distance is approx 4.17km.
        val d = GeoMath.distanceMeters(25.0339, 121.5645, 25.0697, 121.5522)
        assertEquals(4174.0, d, 300.0)
    }

    @Test
    fun `one degree latitude is about 111km`() {
        val d = GeoMath.distanceMeters(0.0, 0.0, 1.0, 0.0)
        assertEquals(111_000.0, d, 2000.0)
    }

    @Test
    fun `bearing due north is zero`() {
        val bearing = GeoMath.bearingDegrees(0.0, 0.0, 1.0, 0.0)
        assertEquals(0.0, bearing, 0.5)
    }

    @Test
    fun `bearing due east is ninety`() {
        val bearing = GeoMath.bearingDegrees(0.0, 0.0, 0.0, 1.0)
        assertEquals(90.0, bearing, 0.5)
    }

    @Test
    fun `compass direction maps bearing to nearest of eight points`() {
        assertEquals("N", GeoMath.compassDirection(0.0))
        assertEquals("NE", GeoMath.compassDirection(45.0))
        assertEquals("E", GeoMath.compassDirection(90.0))
        assertEquals("S", GeoMath.compassDirection(180.0))
        assertEquals("W", GeoMath.compassDirection(270.0))
        assertEquals("N", GeoMath.compassDirection(359.9))
    }

    @Test
    fun `speed is zero when delta seconds is not positive`() {
        assertEquals(0.0, GeoMath.speedMetersPerSecond(100.0, 0.0), 0.0001)
        assertEquals(0.0, GeoMath.speedMetersPerSecond(100.0, -5.0), 0.0001)
    }

    @Test
    fun `ms to kmh conversion`() {
        assertEquals(36.0, GeoMath.msToKmh(10.0), 0.001)
        assertEquals(10.0, GeoMath.kmhToMs(36.0), 0.001)
    }
}
