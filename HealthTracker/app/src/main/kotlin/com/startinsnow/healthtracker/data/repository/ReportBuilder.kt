package com.startinsnow.healthtracker.data.repository

import com.startinsnow.healthtracker.data.db.HealthRecord
import com.startinsnow.healthtracker.data.db.MeasureContext
import com.startinsnow.healthtracker.data.db.RecordType

data class ContextRow(val label: String, val stats: OverviewMath.Stats)
data class OtherRow(val label: String, val count: Int, val latest: String, val average: String)
data class RangeInfo(val low: Double, val high: Double, val stat: OverviewMath.RangeStat)

/** 就醫摘要的內容。只整理使用者自己記錄的數值，不含任何判斷或建議。 */
data class ReportData(
    val days: Int,
    val from: Long,
    val to: Long,
    val name: String,
    val glucose: List<HealthRecord>, // 由舊到新，最多 MAX_ROWS 筆（超過時留下最新的）
    val glucoseTotal: Int,
    val overall: OverviewMath.Stats?,
    val contexts: List<ContextRow>,
    val range: RangeInfo?,
    val others: List<OtherRow>,
)

object ReportBuilder {
    const val MAX_ROWS = 600
    private const val DAY_MS = 86_400_000L

    fun build(all: List<HealthRecord>, name: String, low: Double?, high: Double?, days: Int, now: Long): ReportData {
        val from = now - days * DAY_MS
        val glucoseAll = OverviewMath.window(all, RecordType.BLOOD_GLUCOSE, days, now)
        val glucose = if (glucoseAll.size > MAX_ROWS) glucoseAll.takeLast(MAX_ROWS) else glucoseAll

        val contexts = buildList {
            MeasureContext.entries.forEach { c ->
                OverviewMath.stats(glucoseAll.filter { it.context == c })?.let { add(ContextRow(c.label, it)) }
            }
            OverviewMath.stats(glucoseAll.filter { it.context == null })?.let { add(ContextRow("未標示", it)) }
        }
        val range = OverviewMath.inRange(glucoseAll, low, high)?.let { RangeInfo(low!!, high!!, it) }

        val others = listOf(RecordType.WEIGHT, RecordType.BODY_FAT, RecordType.BLOOD_PRESSURE).mapNotNull { t ->
            val w = OverviewMath.window(all, t, days, now)
            if (w.isEmpty()) return@mapNotNull null
            val avg = w.map { it.value }.average()
            val avg2 = w.mapNotNull { it.value2 }.takeIf { it.isNotEmpty() }?.average()
            OtherRow(
                label = t.label,
                count = w.size,
                latest = display(w.last()),
                average = if (t == RecordType.BLOOD_PRESSURE && avg2 != null) "${Math.round(avg)}/${Math.round(avg2)} ${t.unit}" else "${"%.1f".format(avg)} ${t.unit}",
            )
        }
        return ReportData(days, from, now, name.trim(), glucose, glucoseAll.size, OverviewMath.stats(glucoseAll), contexts, range, others)
    }

    internal fun display(r: HealthRecord): String = when (r.type) {
        RecordType.BLOOD_PRESSURE -> "${r.value.toInt()}/${r.value2?.toInt()} ${r.type.unit}"
        RecordType.BLOOD_GLUCOSE -> "${r.value.toInt()} ${r.type.unit}"
        else -> "${"%.1f".format(r.value)} ${r.type.unit}"
    }
}
