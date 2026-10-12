package com.startinsnow.gpstracker.sync

import android.content.Context

/**
 * 記住 Health Connect 裡「有步數記錄的來源 App」清單，避免每次都掃原始記錄。
 *
 * 來源清單很少變動（手機本身的 package 還帶有每支手機固定的隨機碼，只要看過一次就不會變），
 * 但掃描原始記錄很貴：Google Fit 這類來源每天有大量記錄，每次回到首頁、每 3 小時背景同步都掃，
 * 既慢、又耗電、又吃記憶體。所以：
 *  - 平常直接用快取（至少每 [RESCAN_INTERVAL_MS] 才重掃一次）；
 *  - 重掃只看「最近幾筆」記錄（最新的在前，頁數有上限），並與舊清單取聯集；
 *  - 使用者按「重新整理」時可強制重掃。
 */
class StepOriginCache(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun cached(): Set<String> = prefs.getStringSet(KEY_ORIGINS, emptySet()) ?: emptySet()

    suspend fun origins(reader: HealthConnectStepReader, force: Boolean = false): Set<String> {
        val known = cached()
        val now = System.currentTimeMillis()
        if (!force && known.isNotEmpty() && now - prefs.getLong(KEY_SCANNED_AT, 0L) < RESCAN_INTERVAL_MS) return known

        val recent = reader.recentOrigins().getOrNull() ?: return known
        val union = known + recent
        prefs.edit().putStringSet(KEY_ORIGINS, union).putLong(KEY_SCANNED_AT, now).apply()
        return union
    }

    private companion object {
        const val PREFS = "step_origins"
        const val KEY_ORIGINS = "origins"
        const val KEY_SCANNED_AT = "scanned_at"
        const val RESCAN_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
