package com.startinsnow.gpstracker.ui.components

/** 將毫秒格式化為 HH:MM:SS，供各畫面的持續時間顯示共用。 */
fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

/** 將毫秒格式化為精簡的「Xh Ym」/「Ym」形式，供紀錄列表共用。 */
fun formatDurationShort(ms: Long): String {
    val totalMinutes = ms / 60000
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
