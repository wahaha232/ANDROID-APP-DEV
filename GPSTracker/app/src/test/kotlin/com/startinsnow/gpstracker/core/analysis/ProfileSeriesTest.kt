package com.startinsnow.gpstracker.core.analysis

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSeriesTest {
    @Test
    fun `short series is returned as is`() {
        val v = doubleArrayOf(1.0, 2.0, 3.0)
        assertArrayEquals(v, ProfileSeries.bucketMean(v, 10), 0.0)
    }

    @Test
    fun `long series is averaged into the requested number of points`() {
        val v = DoubleArray(1000) { it.toDouble() }
        val out = ProfileSeries.bucketMean(v, 100)
        assertEquals(100, out.size)
        // 每一段取平均：第 0 段是 0..9 → 4.5；之後單調遞增
        assertEquals(4.5, out.first(), 1e-9)
        assertTrue(out.toList().zipWithNext().all { (a, b) -> b > a })
        assertEquals(994.5, out.last(), 1e-9)
    }

    @Test
    fun `every sample lands in exactly one bucket for awkward sizes`() {
        val v = DoubleArray(1003) { 1.0 }
        val out = ProfileSeries.bucketMean(v, 240)
        assertEquals(240, out.size)
        assertTrue(out.all { kotlin.math.abs(it - 1.0) < 1e-9 })
    }

    @Test
    fun `index follows the finger position and is clamped`() {
        assertEquals(0, ProfileSeries.indexAt(-0.5f, 100))
        assertEquals(99, ProfileSeries.indexAt(1.5f, 100))
        assertEquals(49, ProfileSeries.indexAt(0.5f, 100))
        assertEquals(0, ProfileSeries.indexAt(0.7f, 0))
    }

    @Test
    fun `normalized scales between low and high and centres a flat series`() {
        assertEquals(0f, ProfileSeries.normalized(10.0, 10.0, 30.0), 1e-6f)
        assertEquals(1f, ProfileSeries.normalized(30.0, 10.0, 30.0), 1e-6f)
        assertEquals(0.5f, ProfileSeries.normalized(20.0, 10.0, 30.0), 1e-6f)
        assertEquals(0.5f, ProfileSeries.normalized(7.0, 7.0, 7.0), 1e-6f)
    }
}
