package com.startinsnow.gpstracker.playback

import com.startinsnow.gpstracker.core.geo.GeoMath
import com.startinsnow.gpstracker.core.model.MovementMode
import com.startinsnow.gpstracker.core.model.PhotoType

/** Playback 用的簡化點位，與 Room Entity 脫鉤，讓 Android App 與 Browser(JS) 可以共用同一套邏輯設計。 */
data class PlaybackPoint(
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val speedMps: Double,
    val altitudeMeters: Double?,
    val mode: MovementMode,
    val trusted: Boolean
)

data class PlaybackPhotoMarker(
    val photoId: String,
    val timestampMs: Long,
    val type: PhotoType,
    val latitude: Double?,
    val longitude: Double?
)

data class PlaybackFrame(
    val latitude: Double,
    val longitude: Double,
    val elapsedSinceStartMs: Long,
    val distanceMeters: Double,
    val progress: Double, // 0.0..1.0
    val mode: MovementMode,
    val speedMps: Double,
    val activePhotoIds: List<String>,
    val finished: Boolean
)

/**
 * 對應規格「77. Playback Engine / TrackPlaybackEngine」。
 * 純資料驅動的內插邏輯：給定 Track 的時間序列點位與目前播放時間，回傳應該顯示在地圖上的座標與統計。
 * Android（Compose + MapLibre）與 Browser（Leaflet + JS）皆可依照同一套規則各自實作 UI，
 * 這裡先在 Kotlin 落地並用 Unit Test 驗證內插正確性，Browser 端由 [com.startinsnow.gpstracker.export.TrackHtmlGenerator]
 * 產生對應的 JS 版本（邏輯保持一致）。
 */
class TrackPlaybackEngine(
    private val points: List<PlaybackPoint>,
    private val photos: List<PlaybackPhotoMarker> = emptyList()
) {
    private val trustedPoints = points.filter { it.trusted }.sortedBy { it.timestampMs }
    private val cumulativeDistances: DoubleArray
    val totalDurationMs: Long = trustedPoints.lastOrNull()?.let { it.timestampMs - trustedPoints.first().timestampMs } ?: 0L
    val totalDistanceMeters: Double

    init {
        val distances = DoubleArray(trustedPoints.size)
        var acc = 0.0
        for (i in 1 until trustedPoints.size) {
            acc += GeoMath.distanceMeters(
                trustedPoints[i - 1].latitude, trustedPoints[i - 1].longitude,
                trustedPoints[i].latitude, trustedPoints[i].longitude
            )
            distances[i] = acc
        }
        cumulativeDistances = distances
        totalDistanceMeters = distances.lastOrNull() ?: 0.0
    }

    fun isEmpty(): Boolean = trustedPoints.isEmpty()

    /** @param elapsedMs 從 Track 開始算起的播放進度（毫秒）。 */
    fun frameAt(elapsedMs: Long): PlaybackFrame {
        if (trustedPoints.isEmpty()) {
            return PlaybackFrame(0.0, 0.0, 0L, 0.0, 0.0, MovementMode.UNKNOWN, 0.0, emptyList(), true)
        }
        if (trustedPoints.size == 1) {
            val p = trustedPoints[0]
            return PlaybackFrame(p.latitude, p.longitude, 0L, 0.0, 1.0, p.mode, p.speedMps, activePhotosAt(0L), true)
        }

        val clampedElapsed = elapsedMs.coerceIn(0L, totalDurationMs)
        val targetTimestamp = trustedPoints.first().timestampMs + clampedElapsed

        var lo = 0
        var hi = trustedPoints.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (trustedPoints[mid].timestampMs <= targetTimestamp) lo = mid else hi = mid - 1
        }
        val indexBefore = lo
        val before = trustedPoints[indexBefore]

        if (indexBefore >= trustedPoints.size - 1) {
            return PlaybackFrame(
                before.latitude, before.longitude, clampedElapsed, totalDistanceMeters, 1.0,
                before.mode, before.speedMps, activePhotosAt(clampedElapsed), true
            )
        }

        val after = trustedPoints[indexBefore + 1]
        val segmentDurationMs = (after.timestampMs - before.timestampMs).coerceAtLeast(1L)
        val fraction = ((targetTimestamp - before.timestampMs).toDouble() / segmentDurationMs).coerceIn(0.0, 1.0)

        val lat = before.latitude + (after.latitude - before.latitude) * fraction
        val lon = before.longitude + (after.longitude - before.longitude) * fraction
        val distanceSoFar = cumulativeDistances[indexBefore] +
            (cumulativeDistances[indexBefore + 1] - cumulativeDistances[indexBefore]) * fraction
        val speed = before.speedMps + (after.speedMps - before.speedMps) * fraction
        val progress = if (totalDurationMs > 0) clampedElapsed.toDouble() / totalDurationMs else 1.0

        return PlaybackFrame(
            latitude = lat,
            longitude = lon,
            elapsedSinceStartMs = clampedElapsed,
            distanceMeters = distanceSoFar,
            progress = progress,
            mode = before.mode,
            speedMps = speed,
            activePhotoIds = activePhotosAt(clampedElapsed),
            finished = clampedElapsed >= totalDurationMs
        )
    }

    private fun activePhotosAt(elapsedMs: Long): List<String> {
        if (photos.isEmpty() || trustedPoints.isEmpty()) return emptyList()
        val targetTimestamp = trustedPoints.first().timestampMs + elapsedMs
        return photos.filter { kotlin.math.abs(it.timestampMs - targetTimestamp) <= PHOTO_HIGHLIGHT_WINDOW_MS }
            .map { it.photoId }
    }

    companion object {
        const val PHOTO_HIGHLIGHT_WINDOW_MS = 3_000L
        val SUPPORTED_SPEEDS = listOf(1.0, 2.0, 5.0, 10.0, 20.0)
    }
}
