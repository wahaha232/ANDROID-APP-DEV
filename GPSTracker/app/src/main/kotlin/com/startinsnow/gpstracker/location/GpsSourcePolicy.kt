package com.startinsnow.gpstracker.location

import com.startinsnow.gpstracker.core.model.LocationProvider

/** GPS 與網路定位並存時的取捨規則（純邏輯，可單元測試）。 */
object GpsSourcePolicy {

    /** GPS 最近一次定位在此時間內，網路定位一律忽略。 */
    const val GPS_FRESH_WINDOW_MS = 15_000L

    /**
     * 是否應該忽略這個定位點。網路定位只要 accuracy 不差就會被過濾器當成可信，
     * 和 GPS 點交錯餵進去會造成軌跡之字形、距離被灌水；因此 GPS 剛有定位時忽略網路點。
     *
     * @param lastGpsElapsedMs 最近一次 GPS 定位的 elapsedRealtime；從未收到過傳 [Long.MIN_VALUE]。
     */
    fun shouldIgnoreNetworkFix(provider: LocationProvider, sampleElapsedMs: Long, lastGpsElapsedMs: Long): Boolean =
        provider == LocationProvider.NETWORK &&
            lastGpsElapsedMs != Long.MIN_VALUE &&
            sampleElapsedMs - lastGpsElapsedMs < GPS_FRESH_WINDOW_MS
}
