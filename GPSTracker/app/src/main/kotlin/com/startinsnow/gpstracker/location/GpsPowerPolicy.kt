package com.startinsnow.gpstracker.location

import com.startinsnow.gpstracker.core.model.PointReliability

/**
 * 靜止時的省電／省資料策略（純邏輯，可單元測試）。
 *
 * 長時間錄製時，人如果停在原地（辦公室、等紅燈、休息），GPS 仍以每 1–2 秒一次的頻率喚醒硬體，
 * 而且每個落在雜訊範圍內的低可信度點都寫進資料庫：耗電、資料庫也會長到數萬列卻沒有任何資訊量。
 * 靜止一段時間後：
 *  - 把 GPS 請求間隔拉長（動起來時 Service 會立即還原）；
 *  - 不再寫入「落在雜訊範圍內」的低可信度點（異常跳點 REJECTED 仍照常保留供除錯）。
 */
object GpsPowerPolicy {

    /** 靜止超過這段時間才進入省電模式（太短會在紅燈、停下來拍照時頻繁切換）。 */
    const val STATIONARY_THRESHOLD_MS = 120_000L

    /** 省電模式下的 GPS 請求間隔。 */
    const val STATIONARY_GPS_INTERVAL_MS = 10_000L

    fun isPowerSaving(stationaryMs: Long): Boolean = stationaryMs >= STATIONARY_THRESHOLD_MS

    /** 本次應使用的 GPS 請求間隔：省電模式取「設定的間隔」與 [STATIONARY_GPS_INTERVAL_MS] 中較長者。 */
    fun gpsIntervalMs(baseIntervalMs: Long, stationaryMs: Long): Long =
        if (isPowerSaving(stationaryMs)) maxOf(baseIntervalMs, STATIONARY_GPS_INTERVAL_MS) else baseIntervalMs

    /** 這個點需要寫入資料庫嗎？（可信點與其他情況一律照舊，只省掉靜止時的雜訊點。） */
    fun shouldPersistPoint(reliability: PointReliability, stationaryMs: Long): Boolean =
        !(reliability == PointReliability.LOW_CONFIDENCE && isPowerSaving(stationaryMs))
}
