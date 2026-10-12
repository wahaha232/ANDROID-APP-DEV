package com.startinsnow.gpstracker.sync

import android.content.Context
import androidx.annotation.StringRes
import com.startinsnow.gpstracker.R
import com.startinsnow.gpstracker.data.prefs.StepSource

/** 一筆軌跡最後採用的步數與其來源標記（存入 tracks.stepSource）。 */
data class ResolvedSteps(val steps: Long, val sourceTag: String)

/** tracks.stepSource 的合法值。 */
object StepSourceTag {
    const val SENSOR = "SENSOR"                    // 使用者選擇手機感測器
    const val SENSOR_FALLBACK = "SENSOR_FALLBACK"  // 選了 Health Connect 來源，但讀取失敗／無資料而退回感測器
    const val ZEPP_LIFE = "ZEPP_LIFE"
    const val GOOGLE_FIT = "GOOGLE_FIT"

    /** 步數來源的顯示名稱（字串資源 id，由呼叫端用 Context／stringResource 取字）。 */
    @StringRes
    fun labelRes(tag: String): Int = when (tag) {
        ZEPP_LIFE -> R.string.step_source_zepp
        GOOGLE_FIT -> R.string.step_source_google
        SENSOR_FALLBACK -> R.string.step_source_sensor_fallback
        else -> R.string.step_source_sensor
    }

    fun forSource(source: StepSource): String = when (source) {
        StepSource.ZEPP_LIFE -> ZEPP_LIFE
        StepSource.GOOGLE_FIT -> GOOGLE_FIT
        StepSource.DEVICE_SENSOR -> SENSOR
    }
}

/**
 * 決定一趟軌跡的步數。
 *
 * 只讀「使用者選定的來源」，不再把所有來源相加（會造成 Zepp、Google Fit、手機重複計算）；
 * 選定來源讀不到（未授權、無資料、Zepp 尚未同步）時退回感測器步數，並以 [StepSourceTag.SENSOR_FALLBACK]
 * 標記，之後可由延後補同步或手動「重新同步步數」更新。
 */
class StepResolver(context: Context) {
    private val reader = HealthConnectStepReader(context)

    /** 只讀 Health Connect；失敗時回傳 Result.failure，無資料回傳 0。選感測器時不適用。 */
    suspend fun readHealthConnect(source: StepSource, startMs: Long, endMs: Long): Result<Long> {
        if (!reader.isAvailable()) return Result.failure(IllegalStateException("此裝置未提供 Health Connect"))
        return when (source) {
            StepSource.ZEPP_LIFE -> reader.zeppSteps(startMs, endMs)
            StepSource.GOOGLE_FIT -> reader.googleFitSteps(startMs, endMs)
            StepSource.DEVICE_SENSOR -> Result.failure(IllegalArgumentException("來源為手機感測器"))
        }
    }

    /** 今日各來源明細（package -> 步數）；掃描失敗回傳 failure。 */
    suspend fun readOriginBreakdown(startMs: Long, endMs: Long): Result<Map<String, Long>> {
        if (!reader.isAvailable()) return Result.failure(IllegalStateException("Health Connect unavailable"))
        return runCatching {
            val result = linkedMapOf<String, Long>()
            reader.originsInRange(startMs, endMs).getOrThrow().forEach { pkg ->
                result[pkg] = reader.stepsForOrigin(pkg, startMs, endMs).getOrDefault(0L)
            }
            result
        }
    }

    /** 所有來源去除重複後的合計（Health Connect 依優先順序合併）。 */
    suspend fun readHealthConnectAll(startMs: Long, endMs: Long): Result<Long> {
        if (!reader.isAvailable()) return Result.failure(IllegalStateException("Health Connect unavailable"))
        return reader.allSteps(startMs, endMs)
    }

    suspend fun resolve(source: StepSource, startMs: Long, endMs: Long, sensorSteps: Long): ResolvedSteps {
        if (source == StepSource.DEVICE_SENSOR) return ResolvedSteps(sensorSteps, StepSourceTag.SENSOR)
        val hc = readHealthConnect(source, startMs, endMs).getOrNull()
        return if (hc != null && hc > 0) ResolvedSteps(hc, StepSourceTag.forSource(source))
        else ResolvedSteps(sensorSteps, StepSourceTag.SENSOR_FALLBACK)
    }
}
