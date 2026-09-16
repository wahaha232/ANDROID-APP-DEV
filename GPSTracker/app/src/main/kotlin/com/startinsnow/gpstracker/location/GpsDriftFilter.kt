package com.startinsnow.gpstracker.location

import com.startinsnow.gpstracker.core.geo.GeoMath
import com.startinsnow.gpstracker.core.model.FilteredLocation
import com.startinsnow.gpstracker.core.model.LocationQuality
import com.startinsnow.gpstracker.core.model.LocationSample
import com.startinsnow.gpstracker.core.model.PointReliability

/**
 * GPS 漂移 / 低精度過濾器。純邏輯，不依賴 Android，方便 Unit Test。
 *
 * 規則對應開發規格「8. GPS 漂移過濾」與「10. 室內 GPS 漂移保護」：
 *  - Accuracy 太差（> [ACCURACY_TRUST_THRESHOLD_M]）：永遠不能用來計算 Distance / Speed / Movement Mode。
 *  - 位移量在前後兩點 Accuracy 誤差圓範圍內：視為雜訊抖動，不計入距離。
 *  - 瞬時速度超過物理上限：直接判為異常點（REJECTED）。
 *  - 中等精度但瞬時速度異常偏高：降低可信度，避免誤判 Movement Mode。
 *
 * 只有 [PointReliability.TRUSTED] 的點會推進 anchor（lastTrusted），
 * 因此後續比較永遠是「和上一個可信點」而不是「和上一個原始點」，避免漂移點連鎖污染整條軌跡。
 */
class GpsDriftFilter {

    private var lastTrusted: LocationSample? = null

    /**
     * 上一次相對於 anchor 顯示「異常高速」但尚未被下一點確認的樣本。
     * 用來偵測規格 8 描述的典型漂移模式：原地 → 瞬間跳到遠方 → 又跳回原地。
     * 單一離群點永遠無法通過確認（因為下一點會跳回 anchor 附近，相對 anchor 的速度會變低），
     * 只有「連續兩次都顯示相對 anchor 持續高速」才會被判定為真正的移動。
     */
    private var pendingHighSpeed: LocationSample? = null

    fun evaluate(sample: LocationSample): FilteredLocation {
        val quality = classifyQuality(sample.accuracyMeters)
        val previous = lastTrusted

        if (previous == null) {
            lastTrusted = sample
            return FilteredLocation(sample, PointReliability.TRUSTED, quality)
        }

        val deltaSeconds = (sample.elapsedRealtimeMs - previous.elapsedRealtimeMs) / 1000.0
        if (deltaSeconds <= 0.0) {
            return FilteredLocation(sample, PointReliability.LOW_CONFIDENCE, quality, "non-monotonic timestamp")
        }

        val distanceMeters = GeoMath.distanceMeters(
            previous.latitude, previous.longitude, sample.latitude, sample.longitude
        )
        val impliedSpeedMps = distanceMeters / deltaSeconds

        // Rule 1：物理上不可能的瞬時速度（感測器故障 / GPS 跳點）一律捨棄。
        if (impliedSpeedMps > MAX_PLAUSIBLE_SPEED_MPS) {
            pendingHighSpeed = null
            return FilteredLocation(sample, PointReliability.REJECTED, quality, "impossible speed ${"%.1f".format(impliedSpeedMps)} m/s")
        }

        // Rule 2：Accuracy 太差，永遠不能拿來算距離 / 速度 / Movement Mode。
        if (sample.accuracyMeters > ACCURACY_TRUST_THRESHOLD_M) {
            pendingHighSpeed = null
            if (impliedSpeedMps < STATIONARY_SPEED_MPS) {
                // 幾乎靜止時仍可推進 anchor，避免長時間卡在舊的信任點造成之後距離暴增。
                lastTrusted = sample
            }
            return FilteredLocation(sample, PointReliability.LOW_CONFIDENCE, quality, "poor accuracy ${sample.accuracyMeters}m")
        }

        // Rule 3：相對 anchor 的瞬時速度異常偏高，即使 Accuracy 標示良好也不能立刻採信
        // （這正是規格描述的漂移模式：單點座標本身可能標示「精確」，但實際上是錯誤定位）。
        // 需要「連續兩次都偏離 anchor 很遠」才視為真正持續移動，單一離群點會在下一次比對時
        // 因為又跳回 anchor 附近而自然無法通過確認。
        if (impliedSpeedMps > CONFIRMATION_REQUIRED_SPEED_MPS) {
            val pending = pendingHighSpeed
            if (pending != null) {
                lastTrusted = sample
                pendingHighSpeed = null
                return FilteredLocation(sample, PointReliability.TRUSTED, quality)
            }
            pendingHighSpeed = sample
            return FilteredLocation(sample, PointReliability.LOW_CONFIDENCE, quality, "unconfirmed high speed vs last trusted point")
        }
        pendingHighSpeed = null

        val combinedAccuracy = sample.accuracyMeters + previous.accuracyMeters

        // Rule 4：位移完全可以用兩點的 GPS 誤差圓解釋，視為原地抖動，不計入移動。
        if (distanceMeters <= combinedAccuracy && deltaSeconds < JITTER_WINDOW_SECONDS) {
            return FilteredLocation(sample, PointReliability.LOW_CONFIDENCE, quality, "within GPS noise floor")
        }

        lastTrusted = sample
        return FilteredLocation(sample, PointReliability.TRUSTED, quality)
    }

    /** GPS 恢復或開始新 Track 時呼叫，避免拿舊 Track 的 anchor 比較新資料。 */
    fun reset() {
        lastTrusted = null
        pendingHighSpeed = null
    }

    companion object {
        const val ACCURACY_GOOD_M = 15.0
        const val ACCURACY_NORMAL_M = 50.0
        const val ACCURACY_WEAK_M = 100.0

        /** 高於此精度值，該點不得用於 Distance / Speed / Movement 計算。 */
        const val ACCURACY_TRUST_THRESHOLD_M = 50.0

        /** 300 m/s ≈ 1080 km/h，涵蓋商用客機巡航速度，超過視為感測器異常。 */
        const val MAX_PLAUSIBLE_SPEED_MPS = 300.0

        /** 40 m/s ≈ 144 km/h，超過此瞬時速度一律需要下一點確認才能採信，避免單點漂移噴射。 */
        const val CONFIRMATION_REQUIRED_SPEED_MPS = 40.0

        const val STATIONARY_SPEED_MPS = 0.3
        const val JITTER_WINDOW_SECONDS = 8.0

        fun classifyQuality(accuracyMeters: Float): LocationQuality = when {
            accuracyMeters <= ACCURACY_GOOD_M -> LocationQuality.GOOD
            accuracyMeters <= ACCURACY_NORMAL_M -> LocationQuality.NORMAL
            else -> LocationQuality.WEAK
        }
    }
}
