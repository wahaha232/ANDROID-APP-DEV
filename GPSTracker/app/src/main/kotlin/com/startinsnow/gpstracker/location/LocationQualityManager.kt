package com.startinsnow.gpstracker.location

import com.startinsnow.gpstracker.core.model.LocationProvider
import com.startinsnow.gpstracker.core.model.LocationQuality

/**
 * 對應規格「75. Indoor Location Engine / LocationQualityManager」。
 * 綜合 Provider 與 Accuracy，判斷目前定位品質，並提供 GPS Lost 的逾時判斷。
 * 純邏輯，Android 層（LocationTracker）負責餵入實際資料。
 */
class LocationQualityManager(
    private val lostTimeoutMs: Long = DEFAULT_LOST_TIMEOUT_MS,
    private val firstFixTimeoutMs: Long = FIRST_FIX_TIMEOUT_MS
) {
    private var lastValidElapsedRealtimeMs: Long? = null
    private var trackingStartElapsedMs: Long? = null
    private var receivedFirstFix = false

    /** 本次 Track 是否曾經收到過任何定位。 */
    val hasFix: Boolean get() = receivedFirstFix

    /** 開始新的 Track 時呼叫：重設「從未收到定位」的計時基準。 */
    fun startTracking(nowElapsedRealtimeMs: Long) {
        trackingStartElapsedMs = nowElapsedRealtimeMs
        lastValidElapsedRealtimeMs = null
        receivedFirstFix = false
    }

    fun onLocationReceived(elapsedRealtimeMs: Long) {
        lastValidElapsedRealtimeMs = elapsedRealtimeMs
        receivedFirstFix = true
    }

    /**
     * @return true 代表應標記為 GPS Location Lost。
     *  - 已經收過定位：距離上次有效定位超過 [lostTimeoutMs]。
     *  - 從未收過定位：從 Track 開始超過 [firstFixTimeoutMs] 仍未定位（室內 / 未開 GPS / 權限不足）。
     */
    fun isLost(nowElapsedRealtimeMs: Long): Boolean {
        val last = lastValidElapsedRealtimeMs
        if (last == null) {
            val start = trackingStartElapsedMs ?: return false
            return (nowElapsedRealtimeMs - start) > firstFixTimeoutMs
        }
        return (nowElapsedRealtimeMs - last) > lostTimeoutMs
    }

    fun reset() {
        lastValidElapsedRealtimeMs = null
        trackingStartElapsedMs = null
        receivedFirstFix = false
    }

    companion object {
        const val DEFAULT_LOST_TIMEOUT_MS = 45_000L

        /** 開始記錄後多久還沒收到任何定位就標記 GPS Lost（室內 / GPS 未開啟）。 */
        const val FIRST_FIX_TIMEOUT_MS = 20_000L

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
