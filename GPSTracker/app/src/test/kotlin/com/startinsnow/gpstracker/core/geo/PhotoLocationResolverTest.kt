package com.startinsnow.gpstracker.core.geo

import com.startinsnow.gpstracker.core.model.MovementMode
import com.startinsnow.gpstracker.core.model.PhotoType
import com.startinsnow.gpstracker.data.db.PhotoEntity
import com.startinsnow.gpstracker.data.db.TrackPointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PhotoLocationResolverTest {

    private fun point(t: Long, lat: Double, lon: Double, reliability: String = "TRUSTED") = TrackPointEntity(
        trackId = "t", latitude = lat, longitude = lon, timestampMs = t, elapsedRealtimeMs = t,
        speedMps = null, altitudeMeters = null, accuracyMeters = 5f, provider = "GPS", bearingDegrees = null,
        reliability = reliability, quality = "GOOD", movementMode = MovementMode.WALKING
    )

    private fun photo(t: Long, lat: Double? = null, lon: Double? = null) = PhotoEntity(
        photoId = "p$t", trackId = "t", type = PhotoType.NORMAL, filePath = "x.jpg", timestampMs = t,
        latitude = lat, longitude = lon, accuracyMeters = null, provider = null, speedMps = null, altitudeMeters = null
    )

    @Test
    fun `photo with coordinates is untouched`() {
        val photos = listOf(photo(1000, 1.0, 2.0))
        assertSame(photos, PhotoLocationResolver.fill(photos, listOf(point(1000, 9.0, 9.0))))
    }

    @Test
    fun `photo without coordinates borrows the nearest trusted point by time`() {
        val pts = listOf(point(0, 10.0, 20.0), point(60_000, 11.0, 21.0), point(120_000, 12.0, 22.0))
        val out = PhotoLocationResolver.fill(listOf(photo(70_000)), pts).single()
        assertEquals(11.0, out.latitude!!, 0.0)
        assertEquals(21.0, out.longitude!!, 0.0)
    }

    @Test
    fun `untrusted points are ignored`() {
        val pts = listOf(point(0, 10.0, 20.0), point(60_000, 99.0, 99.0, reliability = "REJECTED"))
        val out = PhotoLocationResolver.fill(listOf(photo(60_000)), pts).single()
        assertEquals(10.0, out.latitude!!, 0.0)
    }

    @Test
    fun `photo far from any point stays unlocated`() {
        val pts = listOf(point(0, 10.0, 20.0))
        val out = PhotoLocationResolver.fill(listOf(photo(PhotoLocationResolver.MAX_GAP_MS + 1)), pts).single()
        assertNull(out.latitude)
    }

    @Test
    fun `no trusted points leaves photos unchanged`() {
        val photos = listOf(photo(1000))
        assertSame(photos, PhotoLocationResolver.fill(photos, emptyList()))
    }
}
