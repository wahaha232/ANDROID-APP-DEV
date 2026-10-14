package com.startinsnow.gpstracker.core.geo

import com.startinsnow.gpstracker.data.db.PhotoEntity
import com.startinsnow.gpstracker.data.db.TrackPointEntity

/**
 * 拍照當下若還沒有 GPS 定位，照片的座標會是 null，詳情頁地圖就標不出來。
 * 這裡依拍照時間，借用軌跡上時間最接近的可信點補上座標（只用於顯示，不改資料庫）。
 */
object PhotoLocationResolver {

    /** 時間差超過這個值就不補：離最近的點太久，位置已不可信。 */
    const val MAX_GAP_MS = 10 * 60 * 1000L

    fun fill(photos: List<PhotoEntity>, points: List<TrackPointEntity>): List<PhotoEntity> {
        if (photos.none { it.latitude == null || it.longitude == null }) return photos
        val trusted = points.filter { it.reliability == "TRUSTED" }.sortedBy { it.timestampMs }
        if (trusted.isEmpty()) return photos
        return photos.map { photo ->
            if (photo.latitude != null && photo.longitude != null) return@map photo
            val nearest = nearest(trusted, photo.timestampMs) ?: return@map photo
            if (kotlin.math.abs(nearest.timestampMs - photo.timestampMs) > MAX_GAP_MS) return@map photo
            photo.copy(latitude = nearest.latitude, longitude = nearest.longitude)
        }
    }

    /** [sorted] 需依 timestampMs 遞增排序且不可為空。 */
    private fun nearest(sorted: List<TrackPointEntity>, timestampMs: Long): TrackPointEntity? {
        var lo = 0
        var hi = sorted.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid].timestampMs < timestampMs) lo = mid + 1 else hi = mid
        }
        val after = sorted[lo]
        val before = sorted.getOrNull(lo - 1) ?: return after
        return if (timestampMs - before.timestampMs <= after.timestampMs - timestampMs) before else after
    }
}
