package com.startinsnow.healthtracker.report

import com.startinsnow.healthtracker.data.db.HealthRecord
import com.startinsnow.healthtracker.data.db.MeasureContext
import com.startinsnow.healthtracker.data.db.RecordType
import java.util.Random

/** 預覽報告版面用的假資料（只在除錯版出現，不會寫進資料庫）。固定亂數種子，每次產生的內容相同。 */
object SampleReport {
    private const val DAY = 86_400_000L

    fun records(now: Long): List<HealthRecord> {
        val rnd = Random(7)
        val out = ArrayList<HealthRecord>()
        var n = 0
        fun add(type: RecordType, v: Double, v2: Double?, ageDays: Double, ctx: MeasureContext? = null) {
            out += HealthRecord(
                id = "sample-${n++}", type = type, value = v, value2 = v2, measuredAt = now - (ageDays * DAY).toLong(),
                createdAt = 1, updatedAt = 1, context = ctx, note = if (n % 11 == 0) "範例備註" else "",
            )
        }
        val ctxs = listOf(MeasureContext.FASTING, MeasureContext.AFTER_MEAL, MeasureContext.BEFORE_MEAL, MeasureContext.BEDTIME)
        for (d in 0 until 40) {
            for ((i, c) in ctxs.withIndex()) {
                if (rnd.nextInt(5) == 0) continue
                val base = when (c) { MeasureContext.FASTING -> 95.0; MeasureContext.AFTER_MEAL -> 135.0; MeasureContext.BEFORE_MEAL -> 105.0; MeasureContext.BEDTIME -> 115.0 }
                add(RecordType.BLOOD_GLUCOSE, Math.round(base + rnd.nextGaussian() * 12).toDouble(), null, d + 0.15 + i * 0.2, c)
            }
            if (d % 3 == 0) add(RecordType.WEIGHT, Math.round((70 + rnd.nextGaussian()) * 10) / 10.0, null, d + 0.3)
            if (d % 7 == 0) add(RecordType.BLOOD_PRESSURE, 118.0 + rnd.nextInt(8), 76.0 + rnd.nextInt(6), d + 0.4)
        }
        return out
    }
}
