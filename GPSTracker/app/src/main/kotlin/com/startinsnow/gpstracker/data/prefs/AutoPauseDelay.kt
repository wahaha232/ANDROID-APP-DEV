package com.startinsnow.gpstracker.data.prefs

/**
 * 「位置靜止多久後自動暫停記錄」的時間設定（以秒儲存，介面以 時／分／秒 呈現）。
 * 純邏輯、不依賴 Android，方便單元測試。
 */
object AutoPauseDelay {
    const val DEFAULT_SECONDS = 60

    /** 太短會因為 GPS 抖動而頻繁暫停／繼續，下限 10 秒。 */
    const val MIN_SECONDS = 10

    /** 上限 23 時 59 分 59 秒。 */
    const val MAX_SECONDS = 24 * 3600 - 1

    data class Hms(val hours: Int, val minutes: Int, val seconds: Int)

    fun clamp(seconds: Int): Int = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)

    fun toMillis(seconds: Int): Long = clamp(seconds) * 1000L

    /** 把總秒數拆成 時／分／秒（先限制在合法範圍內）。 */
    fun toHms(totalSeconds: Int): Hms {
        val t = clamp(totalSeconds)
        return Hms(hours = t / 3600, minutes = (t % 3600) / 60, seconds = t % 60)
    }

    /** 以 Long 計算，避免使用者輸入很大的數字時溢位；欄位留空請先當成 0 傳入。 */
    fun totalSeconds(hours: Long, minutes: Long, seconds: Long): Long = hours * 3600L + minutes * 60L + seconds

    fun isValid(totalSeconds: Long): Boolean = totalSeconds in MIN_SECONDS.toLong()..MAX_SECONDS.toLong()
}
