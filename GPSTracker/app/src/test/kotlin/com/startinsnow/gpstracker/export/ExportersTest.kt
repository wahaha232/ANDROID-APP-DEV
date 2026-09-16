package com.startinsnow.gpstracker.export

import com.startinsnow.gpstracker.core.model.MovementMode
import com.startinsnow.gpstracker.core.model.PhotoType
import com.startinsnow.gpstracker.core.model.TrackStatus
import com.startinsnow.gpstracker.core.model.UploadStatus
import com.startinsnow.gpstracker.data.db.MovementSegmentEntity
import com.startinsnow.gpstracker.data.db.PhotoEntity
import com.startinsnow.gpstracker.data.db.TrackEntity
import com.startinsnow.gpstracker.data.db.TrackPointEntity
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 匯出格式驗證（規格 51/52/53/47~50）：
 *  - GPX 必須是合法 XML，且只輸出 TRUSTED 點。
 *  - JSON 必須包含 Track / Points / Photos / Modes / Statistics。
 *  - CSV 必須有表頭且每個點一列。
 *  - Browser HTML 使用 Leaflet（絕不出現 Google Maps），並內嵌可播放的點位。
 */
class ExportersTest {

    private fun track() = TrackEntity(
        trackId = "t1",
        startTimeMs = 1_700_000_000_000L,
        endTimeMs = 1_700_000_600_000L,
        status = TrackStatus.FINISHED,
        distanceMeters = 1234.5,
        durationMs = 600_000L,
        avgSpeedMps = 2.0,
        maxSpeedMps = 5.0,
        stepCount = 777L,
        dominantMode = MovementMode.WALKING,
        uploadStatus = UploadStatus.NOT_UPLOADED,
        gpsPointCount = 2,
        photoCount = 1,
        maxAltitudeMeters = 120.0,
        minAltitudeMeters = 100.0,
        totalAscentMeters = 20.0,
        totalDescentMeters = 0.0,
        startLatitude = 25.0,
        startLongitude = 121.0,
        endLatitude = 25.002,
        endLongitude = 121.0,
        modeDistributionJson = """{"WALKING":100.0}"""
    )

    private fun point(id: Long, lat: Double, timeMs: Long, reliability: String = "TRUSTED") = TrackPointEntity(
        pointId = id,
        trackId = "t1",
        latitude = lat,
        longitude = 121.0,
        timestampMs = timeMs,
        elapsedRealtimeMs = timeMs,
        speedMps = 1.5f,
        altitudeMeters = 100.0,
        accuracyMeters = 8f,
        provider = "GPS",
        bearingDegrees = 0f,
        reliability = reliability,
        quality = "GOOD",
        movementMode = MovementMode.WALKING
    )

    private fun photo() = PhotoEntity(
        photoId = "p1",
        trackId = "t1",
        type = PhotoType.START,
        filePath = "/data/user/0/com.startinsnow.gpstracker/files/photos/t1/START_20260101_120000.jpg",
        timestampMs = 1_700_000_000_000L,
        latitude = 25.0,
        longitude = 121.0,
        accuracyMeters = 8f,
        provider = "GPS",
        speedMps = 0f,
        altitudeMeters = 100.0
    )

    private fun data() = ExportTrackData(
        track = track(),
        points = listOf(
            point(1, 25.0, 1_700_000_000_000L),
            point(2, 25.001, 1_700_000_300_000L, reliability = "REJECTED"),
            point(3, 25.002, 1_700_000_600_000L)
        ),
        photos = listOf(photo()),
        segments = listOf(
            MovementSegmentEntity(segmentId = 1, trackId = "t1", mode = MovementMode.WALKING, startTimeMs = 1_700_000_000_000L, endTimeMs = 1_700_000_600_000L)
        ),
        outages = emptyList()
    )

    @Test
    fun `gpx is valid xml and only contains trusted points`() {
        val gpx = GpxExporter.generate(data())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(gpx.toByteArray(Charsets.UTF_8)))
        assertEquals("gpx", doc.documentElement.tagName)
        assertEquals("1.1", doc.documentElement.getAttribute("version"))
        // 3 個點中只有 2 個是 TRUSTED
        assertEquals(2, doc.getElementsByTagName("trkpt").length)
        // 照片要輸出成 wpt
        assertEquals(1, doc.getElementsByTagName("wpt").length)
        assertTrue(gpx.contains("<ele>"))
    }

    @Test
    fun `json contains track points photos modes and statistics`() {
        val json = JsonExporter.generate(data())
        assertTrue(json.contains("\"points\""))
        assertTrue(json.contains("\"photos\""))
        assertTrue(json.contains("\"modes\""))
        assertTrue(json.contains("\"statistics\""))
        assertTrue(json.contains("\"schemaVersion\": 1"))
        // 三個點都要保留（含 REJECTED，供除錯 / 完整還原）
        assertEquals(3, Regex("\"reliability\"").findAll(json).count())
    }

    @Test
    fun `csv has header and one row per point`() {
        val lines = CsvExporter.generate(data()).trim().lines()
        assertEquals("timestamp,latitude,longitude,speed_mps,altitude_m,accuracy_m,provider,reliability,quality,mode", lines[0])
        assertEquals(4, lines.size)
        assertTrue(lines[2].contains("REJECTED"))
    }

    @Test
    fun `browser html uses leaflet embeds playable points and no google maps`() {
        val html = TrackHtmlGenerator.generate(data())
        assertTrue(html.contains("leaflet"))
        assertFalse(html.contains("google.maps"))
        assertTrue(html.contains("TRACK_POINTS"))
        assertTrue(html.contains("photos/START_20260101_120000.jpg"))
        // 只把 TRUSTED 點放進播放陣列（2 個可信點：25.0 與 25.002，REJECTED 的 25.001 不可出現）
        assertEquals(1, Regex("\\[25\\.0,").findAll(html).count())
        assertEquals(1, Regex("\\[25\\.002,").findAll(html).count())
        assertFalse(Regex("\\[25\\.001,").containsMatchIn(html))
    }

    @Test
    fun `drive relative path follows the year month folder structure`() {
        val path = TrackZipExporter.relativeDrivePath(1_700_000_000_000L)
        assertTrue(path.startsWith("GPS TRACKER/"))
        assertEquals(4, path.split("/").size)
    }
}
