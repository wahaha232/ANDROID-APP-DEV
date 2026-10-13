package com.startinsnow.gpstracker.movement

/** 「靜止達設定時間就自動暫停」的判斷。純函式，方便單元測試。 */
object AutoPausePolicy {
    /**
     * @param stationarySinceElapsedMs 偵測器判定「靜止」的起點（elapsedRealtime）；null 表示目前不是靜止。
     * @param thresholdMs 使用者設定的靜止時間（見 [com.startinsnow.gpstracker.data.prefs.AutoPauseDelay]）。
     */
    fun shouldAutoPause(
        enabled: Boolean,
        isPaused: Boolean,
        isAutoPaused: Boolean,
        stationarySinceElapsedMs: Long?,
        nowElapsedMs: Long,
        thresholdMs: Long
    ): Boolean =
        enabled && !isPaused && !isAutoPaused &&
            stationarySinceElapsedMs != null &&
            (nowElapsedMs - stationarySinceElapsedMs) >= thresholdMs
}
