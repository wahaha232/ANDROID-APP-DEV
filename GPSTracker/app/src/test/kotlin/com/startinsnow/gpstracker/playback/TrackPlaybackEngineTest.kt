package com.startinsnow.gpstracker.playback

import com.startinsnow.gpstracker.core.model.MovementMode
import com.startinsnow.gpstracker.core.model.PhotoType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackPlaybackEngineTest {

    private fun point(t: Long, lat: Double, lon: Double, speed: Double = 0.0, trusted: Boolean = true) =
        PlaybackPoint(t, lat, lon, speed, null, MovementMode.WALKING, trusted)

    @Test
    fun `frame at start returns first point with zero progress`() {
        val engine = TrackPlaybackEngine(
            listOf(point(0L, 25.0, 121.0), point(10_000L, 25.001, 121.0))
        )
        val frame = engine.frameAt(0L)
        assertEquals(25.0, frame.latitude, 0.00001)
        assertEquals(0.0, frame.progress, 0.0001)
    }

    @Test
    fun `frame at end returns last point and marks finished`() {
        val engine = TrackPlaybackEngine(
            listOf(point(0L, 25.0, 121.0), point(10_000L, 25.001, 121.0))
        )
        val frame = engine.frameAt(10_000L)
        assertEquals(25.001, frame.latitude, 0.00001)
        assertTrue(frame.finished)
        assertEquals(1.0, frame.progress, 0.0001)
    }

    @Test
    fun `frame at midpoint linearly interpolates position`() {
        val engine = TrackPlaybackEngine(
            listOf(point(0L, 25.0, 121.0), point(10_000L, 25.002, 121.0))
        )
        val frame = engine.frameAt(5_000L)
        assertEquals(25.001, frame.latitude, 0.0001)
        assertEquals(0.5, frame.progress, 0.001)
    }

    @Test
    fun `distance accumulates correctly up to current progress`() {
        val engine = TrackPlaybackEngine(
            listOf(
                point(0L, 25.0, 121.0),
                point(5_000L, 25.001, 121.0),
                point(10_000L, 25.002, 121.0)
            )
        )
        val fullDistance = engine.totalDistanceMeters
        val midFrame = engine.frameAt(5_000L)
        assertEquals(fullDistance / 2.0, midFrame.distanceMeters, fullDistance * 0.05)
    }

    @Test
    fun `elapsed beyond total duration clamps to finished last point`() {
        val engine = TrackPlaybackEngine(
            listOf(point(0L, 25.0, 121.0), point(10_000L, 25.001, 121.0))
        )
        val frame = engine.frameAt(999_999L)
        assertEquals(25.001, frame.latitude, 0.00001)
        assertTrue(frame.finished)
    }

    @Test
    fun `untrusted points are excluded from playback route`() {
        val engine = TrackPlaybackEngine(
            listOf(
                point(0L, 25.0, 121.0),
                point(5_000L, 40.0, 130.0, trusted = false), // drift outlier, must be skipped
                point(10_000L, 25.001, 121.0)
            )
        )
        val frame = engine.frameAt(5_000L)
        // Should interpolate directly between the two trusted points, not jump toward the drift point.
        assertEquals(25.0005, frame.latitude, 0.0005)
    }

    @Test
    fun `photo becomes active near its capture timestamp`() {
        val photo = PlaybackPhotoMarker("p1", timestampMs = 5_000L, type = PhotoType.NORMAL, latitude = 25.001, longitude = 121.0)
        val engine = TrackPlaybackEngine(
            listOf(point(0L, 25.0, 121.0), point(10_000L, 25.002, 121.0)),
            listOf(photo)
        )
        val frame = engine.frameAt(5_000L)
        assertTrue(frame.activePhotoIds.contains("p1"))
    }

    @Test
    fun `empty track does not crash and reports finished`() {
        val engine = TrackPlaybackEngine(emptyList())
        assertTrue(engine.isEmpty())
        val frame = engine.frameAt(0L)
        assertTrue(frame.finished)
    }

    @Test
    fun `supported playback speeds match spec - 1x 2x 5x 10x 20x`() {
        assertEquals(listOf(1.0, 2.0, 5.0, 10.0, 20.0), TrackPlaybackEngine.SUPPORTED_SPEEDS)
    }
}
