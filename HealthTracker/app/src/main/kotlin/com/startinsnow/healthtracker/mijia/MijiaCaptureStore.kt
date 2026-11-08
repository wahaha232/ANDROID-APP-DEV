package com.startinsnow.healthtracker.mijia

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * 米家畫面讀取的即時狀態。無障礙服務與 App 在同一個行程，所以用一個 process 內的 StateFlow
 * 讓設定頁可以即時看到「正在等畫面 → 讀到什麼 → 新增幾筆」，不必輪詢 SharedPreferences。
 * 最後一次結果仍會寫進 SharedPreferences，App 重開後還看得到。
 */
object MijiaCaptureStore {
    /** 一次畫面解析的結果；[reading] 為 null 表示這次有看到體脂計頁面但解析失敗。 */
    data class Event(
        val seq: Long,
        val at: Long,
        val reading: MijiaReading?,
        val inserted: Int = 0,
        val skipped: Int = 0,
        val raw: List<String> = emptyList(),
        val message: String? = null,
    )

    /** 最近一次成功讀取（從 SharedPreferences 還原）。 */
    data class Last(
        val readAt: Long,
        val measuredAt: Long,
        val weightKg: Double?,
        val bodyFatPercent: Double?,
        val inserted: Int,
        val skipped: Int,
        val timeEstimated: Boolean,
    )

    private val seq = AtomicLong(0)
    private val _event = MutableStateFlow<Event?>(null)
    val event: StateFlow<Event?> = _event.asStateFlow()

    @Volatile private var service: MijiaAccessibilityService? = null

    fun attach(service: MijiaAccessibilityService) {
        this.service = service
    }

    fun detach(service: MijiaAccessibilityService) {
        if (this.service === service) this.service = null
    }

    fun isServiceConnected(): Boolean = service != null

    /** 請服務立刻重新解析目前畫面（例如使用者剛從米家切回來）；服務沒開啟回傳 false。 */
    fun requestRefresh(): Boolean = service?.captureNow(force = true) ?: false

    fun publish(event: Event) {
        _event.value = event
    }

    fun nextSeq(): Long = seq.incrementAndGet()

    fun saveLast(context: Context, event: Event) {
        val r = event.reading ?: return
        context.getSharedPreferences(MijiaAccessibilityService.PREFS, Context.MODE_PRIVATE).edit()
            .putString(
                MijiaAccessibilityService.KEY_LAST,
                "${event.at}|${r.measuredAt}|${r.weightKg ?: ""}|${r.bodyFatPercent ?: ""}|${event.inserted}|${event.skipped}|${if (r.timeEstimated) 1 else 0}",
            )
            .putString(KEY_RAW, event.raw.joinToString("\n").take(MAX_RAW_CHARS))
            .apply()
    }

    fun loadLast(context: Context): Last? {
        val raw = context.getSharedPreferences(MijiaAccessibilityService.PREFS, Context.MODE_PRIVATE)
            .getString(MijiaAccessibilityService.KEY_LAST, null) ?: return null
        val p = raw.split('|')
        if (p.size < 6) return null
        val readAt = p[0].toLongOrNull() ?: return null
        val measuredAt = p[1].toLongOrNull() ?: return null
        return Last(
            readAt = readAt,
            measuredAt = measuredAt,
            weightKg = p[2].takeIf { it.isNotEmpty() }?.toDoubleOrNull(),
            bodyFatPercent = p[3].takeIf { it.isNotEmpty() }?.toDoubleOrNull(),
            inserted = p[4].toIntOrNull() ?: 0,
            skipped = p[5].toIntOrNull() ?: 0,
            timeEstimated = p.getOrNull(6) == "1",
        )
    }

    fun loadRaw(context: Context): String? =
        context.getSharedPreferences(MijiaAccessibilityService.PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RAW, null)

    private const val KEY_RAW = "last_raw"
    private const val MAX_RAW_CHARS = 6_000
}
