package com.startinsnow.gpstracker.location

import com.startinsnow.gpstracker.core.model.LocationProvider
import com.startinsnow.gpstracker.core.model.LocationQuality

/**
 * 對應規格「75. Indoor Location Engine / LocationQualityManager」。
 * 綜合 Provider 與 Accuracy，判斷目前定位品質，並提供 GPS Lost 的逾時判斷。
 * 純邏輯，Android 層（LocationTracker）負責餵入實際資料。
 */
class LocationQualityManager(
    private val lostTimeoutMs: Long = DEFAULT_LOST_TIMEOUT_MS
) {
    private var lastValidElapsedRealtimeMs: Long? = null

    fun onLocationReceived(elapsedRealtimeMs: Long) {
        lastValidElapsedRealtimeMs = elapsedRealtimeMs
    }

    /** @return true 代表距離上次有效定位已超過逾時，應標記為 GPS Location Lost。 */
    fun isLost(nowElapsedRealtimeMs: Long): Boolean {
        val last = lastValidElapsedRealtimeMs ?: return false
        return (nowElapsedRealtimeMs - last) > lostTimeoutMs
    }

    fun reset() {
        lastValidElapsedRealtimeMs = null
    }

    companion object {
        const val DEFAULT_LOST_TIMEOUT_MS = 45_000L

        fun qualityFor(provider: LocationProvider, accuracyMeters: Float): LocationQuality {
            // Network/Passive 來源精度天生較差，直接視為 WEAK 起跳，避免顯示成看似精準的 GPS。
            val base = GpsDriftFilter.classifyQuality(accuracyMeters)
            return if (provider != LocationProvider.GPS && base == LocationQuality.GOOD) {
                LocationQuality.NORMAL
            } else {
                base
            }
        }

        fun accuracyLabel(quality: LocationQuality, accuracyMeters: Float, provider: LocationProvider): String {
            val meters = accuracyMeters.toInt()
            return when {
                quality == LocationQuality.GOOD -> "📍 GPS ${meters}m"
                provider == LocationProvider.NETWORK -> "📍 Network ${meters}m"
                quality == LocationQuality.WEAK -> "⚠️ 定位精度較低 (${meters}m)"
                else -> "📍 ${meters}m"
            }
        }
    }
}
