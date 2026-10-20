package com.startinsnow.gpstracker.export

import com.startinsnow.gpstracker.core.model.MovementMode
import com.startinsnow.gpstracker.core.model.PhotoType
import com.startinsnow.gpstracker.core.model.TrackStatus
import com.startinsnow.gpstracker.core.model.UploadStatus
import com.startinsnow.gpstracker.core.privacy.PrivacyZone
import com.startinsnow.gpstracker.data.db.GpsOutageEntity
import com.startinsnow.gpstracker.data.db.PhotoEntity
import com.startinsnow.gpstracker.data.db.TrackEntity
import com.startinsnow.gpstracker.data.db.TrackPointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPrivacyTest {
    // 假座標：起點 (25.0, 121.0) 是「家」，往北每點約 111 公尺。
    private val home = PrivacyZone(25.0, 121.0, 200)

    private fun track() = TrackEntity(
        trackId = "t", startTimeMs = 0, endTimeMs = 1000, status = TrackStatus.FINISHED, distanceMeters = 1.0, durationMs = 1000,
        avgSpeedMps = 1.0, maxSpeedMps = 1.0, stepCount = 0, dominantMode = MovementMode.WALKING, uploadStatus = UploadStatus.NOT_UPLOADED,
        gpsPointCount = 8, photoCount = 3, maxAltitudeMeters = null, minAltitudeMeters = null, totalAscentMeters = 0.0, totalDescentMeters = 0.0,
        startLatitude = 25.0, startLongitude = 121.0, endLatitude = 25.007, endLongitude = 121.0, modeDistributionJson = "{}",
    )

    private fun point(index: Int) = TrackPointEntity(
        pointId = index.toLong(), trackId = "t", latitude = 25.0 + index * 0.001, longitude = 121.0, timestampMs = index * 100L,
        elapsedRealtimeMs = index * 100L, speedMps = null, altitudeMeters = null, accuracyMeters = 5f, provider = "GPS",
        bearingDegrees = null, reliability = "TRUSTED", quality = "GOOD", movementMode = MovementMode.WALKING,
    )

    private fun photo(id: String, lat: Double?, timeMs: Long) = PhotoEntity(
        photoId = id, trackId = "t", type = PhotoType.NORMAL, filePath = "/x/$id.jpg", timestampMs = timeMs,
        latitude = lat, longitude = lat?.let { 121.0 }, accuracyMeters = null, provider = null, speedMps = null, altitudeMeters = null,
    )

    private fun data() = ExportTrackData(
        track = track(),
        points = (0..7).map(::point),
        photos = listOf(photo("home", 25.0, 0), photo("mid", 25.004, 400), photo("nocoords-late", null, 700)),
        segments = emptyList(),
        outages = listOf(GpsOutageEntity(trackId = "t", startTimestampMs = 0, endTimestampMs = 50), GpsOutageEntity(trackId = "t", startTimestampMs = 300, endTimestampMs = 350)),
    )

    @Test
    fun noZones_returnsDataUnchanged() {
        val original = data()
        assertSame(original, ExportPrivacy.apply(original, emptyList()))
    }

    @Test
    fun trimsEndsAndFixesStartEndCoordinates() {
        val result = ExportPrivacy.apply(data(), listOf(home))
        // 家的半徑 200 公尺：前 2 個點（0、111 公尺）被裁掉，之後的點保留。
        assertTrue(result.points.first().latitude > 25.0019)
        assertEquals(result.points.first().latitude, result.track.startLatitude!!, 0.0)
        assertEquals(result.points.last().latitude, result.track.endLatitude!!, 0.0)
    }

    @Test
    fun dropsPhotosInsideZoneAndOutsideTrimmedTimeRange() {
        val result = ExportPrivacy.apply(data(), listOf(home))
        assertEquals(listOf("mid", "nocoords-late"), result.photos.map { it.photoId })
    }

    @Test
    fun dropsOutagesOutsideTrimmedTimeRange() {
        val result = ExportPrivacy.apply(data(), listOf(home))
        assertEquals(listOf(300L), result.outages.map { it.startTimestampMs })
    }

    @Test
    fun trackEntirelyInsideZone_exportsNoLocationData() {
        val big = PrivacyZone(25.0, 121.0, 2000)
        val result = ExportPrivacy.apply(data(), listOf(big))
        assertTrue(result.points.isEmpty())
        assertTrue(result.photos.isEmpty())
        assertNull(result.track.startLatitude)
        assertNull(result.track.endLongitude)
    }
}
