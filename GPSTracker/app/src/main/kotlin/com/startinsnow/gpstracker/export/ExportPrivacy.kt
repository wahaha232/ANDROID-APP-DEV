package com.startinsnow.gpstracker.export

import com.startinsnow.gpstracker.core.privacy.PrivacyZone
import com.startinsnow.gpstracker.core.privacy.PrivacyZones

/**
 * 「匯出時套用隱私區域」：產生給別人看的匯出包（分享 ZIP）時，和網頁上傳一樣把開頭與結尾落在隱私區域內的點移除，
 * 區域內的照片不匯出；起終點座標改成裁掉之後的第一點與最後一點。本機資料庫和上傳到自己 Google Drive 的備份仍是完整的。
 * 純邏輯，可用 JVM 單元測試。
 */
object ExportPrivacy {
    fun apply(data: ExportTrackData, zones: List<PrivacyZone>): ExportTrackData {
        if (zones.isEmpty()) return data
        val points = PrivacyZones.trimEnds(data.points, zones, { it.latitude }, { it.longitude })
        val from = points.minOfOrNull { it.timestampMs }
        val to = points.maxOfOrNull { it.timestampMs }
        // 裁掉之後沒有任何點：整筆都在隱私區域內，什麼位置相關的資料都不輸出。
        if (from == null || to == null) {
            return data.copy(
                track = data.track.copy(startLatitude = null, startLongitude = null, endLatitude = null, endLongitude = null),
                points = emptyList(),
                photos = emptyList(),
                segments = emptyList(),
                outages = emptyList(),
            )
        }
        val first = points.first()
        val last = points.last()
        return data.copy(
            track = data.track.copy(
                startLatitude = first.latitude,
                startLongitude = first.longitude,
                endLatitude = last.latitude,
                endLongitude = last.longitude,
            ),
            points = points,
            // 照片：落在隱私區域內、或拍攝時間在被裁掉的開頭／結尾（還在家附近）的都不匯出。
            photos = data.photos.filter { photo ->
                val lat = photo.latitude
                val lon = photo.longitude
                photo.timestampMs in from..to && (lat == null || lon == null || !PrivacyZones.isInside(lat, lon, zones))
            },
            segments = data.segments.filter { (it.endTimeMs ?: Long.MAX_VALUE) >= from && it.startTimeMs <= to },
            outages = data.outages.filter { (it.endTimestampMs ?: Long.MAX_VALUE) >= from && it.startTimestampMs <= to },
        )
    }
}
