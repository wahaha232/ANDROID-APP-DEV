package com.startinsnow.gpstracker.sensor

/**
 * TYPE_STEP_COUNTER 回傳的是「開機以來累積步數」，必須自行換算成「本次 Track 步數」，
 * 並處理手機重開機造成的 Sensor Reset（規格 17~18、83）。純邏輯，方便 Unit Test。
 */
class StepCounterMath {

    private var baseline: Long? = null
    private var lastRawCount: Long = 0
    private var accumulatedSteps: Long = 0

    /**
     * @param rawCount Sensor 回報的累積步數（自開機以來）。
     * @return 從 Track 開始到目前為止的步數，恆為 >= 0。
     */
    fun onRawCount(rawCount: Long): Long {
        val currentBaseline = baseline
        if (currentBaseline == null) {
            baseline = rawCount
            lastRawCount = rawCount
            return 0L
        }

        if (rawCount < lastRawCount) {
            // Counter 變小：代表手機重開機或 Sensor 被重置，先把目前累積的步數存起來，
            // 再用新的原始值當作新的基準點繼續累加，避免出現負數或暴衝的步數。
            accumulatedSteps += (lastRawCount - currentBaseline)
            baseline = rawCount
        }

        lastRawCount = rawCount
        val newBaseline = baseline ?: rawCount
        val delta = accumulatedSteps + (rawCount - newBaseline)
        return delta.coerceAtLeast(0L)
    }

    fun reset() {
        baseline = null
        lastRawCount = 0L
        accumulatedSteps = 0L
    }

    /**
     * Crash Recovery 用：App 重啟後從資料庫讀到先前已累積的步數，
     * 用它當作新的起算基礎，讓步數可以接續而不是歸零重算（規格 57/83）。
     */
    fun seedAccumulated(steps: Long) {
        accumulatedSteps = steps.coerceAtLeast(0L)
        baseline = null
        lastRawCount = 0L
    }

    fun currentTrackSteps(): Long {
        val currentBaseline = baseline ?: return 0L
        return (accumulatedSteps + (lastRawCount - currentBaseline)).coerceAtLeast(0L)
    }
}
