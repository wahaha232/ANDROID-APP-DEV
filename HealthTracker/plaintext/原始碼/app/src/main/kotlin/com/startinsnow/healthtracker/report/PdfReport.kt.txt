package com.startinsnow.healthtracker.report

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.startinsnow.healthtracker.data.repository.OverviewMath
import com.startinsnow.healthtracker.data.repository.ReportData
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 把 ReportData 畫成 A4 的 PDF（用系統內建的 PdfDocument，中文使用系統字型）。 */
object PdfReport {
    private const val W = 595
    private const val H = 842
    private const val M = 40f
    private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val dateOnly = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val monthDay = DateTimeFormatter.ofPattern("MM/dd")

    private fun paint(size: Float, bold: Boolean = false, color: Int = Color.rgb(26, 27, 36)) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    fun write(data: ReportData, generatedAt: Long, zone: ZoneId, out: OutputStream) {
        val doc = PdfDocument()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var c: Canvas? = null
        var y = 0f

        fun footer() {
            val cv = c ?: return
            val p = paint(8f, color = Color.GRAY)
            cv.drawText("此報告由「血糖記錄」App 依您自行記錄的數值整理，不含任何醫療判斷；如有疑問請諮詢醫師。", M, H - 24f, p)
            cv.drawText("第 $pageNo 頁", W - M - 30f, H - 24f, p)
        }
        fun newPage() {
            page?.let { footer(); doc.finishPage(it) }
            pageNo++
            val pg = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            page = pg
            c = pg.canvas
            y = M
        }
        fun need(h: Float) { if (y + h > H - 50f) newPage() }
        fun text(s: String, p: Paint, x: Float = M) { c!!.drawText(s, x, y, p) }

        val title = paint(22f, bold = true, color = Color.rgb(33, 44, 158))
        val h2 = paint(14f, bold = true)
        val body = paint(11f)
        val small = paint(9f, color = Color.DKGRAY)
        val line = Paint().apply { color = Color.rgb(211, 213, 230); strokeWidth = 0.8f }

        newPage()
        y += 18f; text("血糖記錄報告", title); y += 20f
        val period = "${dateOnly.format(Instant.ofEpochMilli(data.from).atZone(zone))} ～ ${dateOnly.format(Instant.ofEpochMilli(data.to).atZone(zone))}（近 ${data.days} 天）"
        text("期間：$period", body); y += 15f
        text("產生時間：${dateTime.format(Instant.ofEpochMilli(generatedAt).atZone(zone))}" + if (data.name.isNotEmpty()) "　　姓名：${data.name}" else "", body); y += 24f

        // 血糖統計
        text("血糖統計（mg/dL）", h2); y += 18f
        val o = data.overall
        if (o == null) {
            text("這段期間沒有血糖紀錄。", body); y += 18f
        } else {
            text("共 ${data.glucoseTotal} 筆　平均 ${"%.0f".format(o.avg)}　最低 ${"%.0f".format(o.min)}　最高 ${"%.0f".format(o.max)}", body); y += 16f
            data.range?.let {
                text("落在您設定的範圍（${it.low.toInt()}–${it.high.toInt()}）內：${it.stat.inRange}/${it.stat.total} 筆（${it.stat.percent}%）", body); y += 16f
            }
            y += 6f
            // 依情境
            if (data.contexts.isNotEmpty()) {
                need(24f + 16f * (data.contexts.size + 1))
                text("依測量情境", h2); y += 16f
                val xs = floatArrayOf(M, M + 110f, M + 190f, M + 270f, M + 350f)
                listOf("情境", "筆數", "平均", "最低", "最高").forEachIndexed { i, s -> c!!.drawText(s, xs[i], y, small) }
                y += 4f; c!!.drawLine(M, y, W - M, y, line); y += 13f
                data.contexts.forEach { row ->
                    listOf(row.label, "${row.stats.count}", "%.0f".format(row.stats.avg), "%.0f".format(row.stats.min), "%.0f".format(row.stats.max))
                        .forEachIndexed { i, s -> c!!.drawText(s, xs[i], y, body) }
                    y += 16f
                }
                y += 8f
            }
            // 趨勢圖
            if (data.glucose.size >= 2) {
                need(190f)
                text("血糖趨勢", h2); y += 8f
                drawChart(c!!, data, zone, M, y, W - 2 * M, 150f)
                y += 150f + 28f
            }
        }

        // 其他量測
        if (data.others.isNotEmpty()) {
            need(24f + 16f * data.others.size)
            text("其他量測（同一期間）", h2); y += 16f
            data.others.forEach {
                text("${it.label}：${it.count} 筆　最新 ${it.latest}　平均 ${it.average}", body); y += 16f
            }
            y += 8f
        }

        // 明細
        if (data.glucose.isNotEmpty()) {
            need(60f)
            text("血糖明細" + if (data.glucoseTotal > data.glucose.size) "（僅列最新 ${data.glucose.size} 筆，統計含全部 ${data.glucoseTotal} 筆）" else "", h2); y += 18f
            val xs = floatArrayOf(M, M + 130f, M + 200f, M + 260f)
            fun header() {
                listOf("時間", "血糖", "情境", "備註").forEachIndexed { i, s -> c!!.drawText(s, xs[i], y, small) }
                y += 4f; c!!.drawLine(M, y, W - M, y, line); y += 13f
            }
            header()
            data.glucose.reversed().forEach { r ->
                if (y + 16f > H - 50f) { newPage(); header() }
                c!!.drawText(dateTime.format(Instant.ofEpochMilli(r.measuredAt).atZone(zone)), xs[0], y, body)
                c!!.drawText("${r.value.toInt()}", xs[1], y, body)
                c!!.drawText(r.context?.label ?: "", xs[2], y, body)
                c!!.drawText(r.note.take(22), xs[3], y, body)
                y += 15f
            }
        }

        footer()
        page?.let { doc.finishPage(it) }
        doc.writeTo(out)
        doc.close()
    }

    private fun drawChart(cv: Canvas, data: ReportData, zone: ZoneId, x0: Float, y0: Float, w: Float, h: Float) {
        val pts = data.glucose
        val band = data.range
        val scale = OverviewMath.niceScale(
            minOf(pts.minOf { it.value }, band?.low ?: Double.MAX_VALUE),
            maxOf(pts.maxOf { it.value }, band?.high ?: -Double.MAX_VALUE),
        )
        val left = x0 + 30f
        val width = w - 36f
        val t0 = pts.first().measuredAt
        val t1 = pts.last().measuredAt
        fun px(t: Long) = left + if (t1 == t0) width / 2 else (t - t0).toFloat() / (t1 - t0) * width
        fun py(v: Double) = y0 + h - ((v - scale.min) / (scale.max - scale.min)).toFloat() * h
        band?.let {
            cv.drawRect(left, py(it.high), left + width, py(it.low), Paint().apply { color = Color.argb(40, 14, 124, 143) })
        }
        val grid = Paint().apply { color = Color.rgb(222, 224, 238); strokeWidth = 0.6f }
        val lab = paint(8f, color = Color.DKGRAY)
        scale.ticks.forEach { tick ->
            cv.drawLine(left, py(tick), left + width, py(tick), grid)
            cv.drawText("%.0f".format(tick), x0, py(tick) + 3f, lab)
        }
        listOf(t0, t1).distinct().forEachIndexed { i, t ->
            val s = monthDay.format(Instant.ofEpochMilli(t).atZone(zone))
            cv.drawText(s, if (i == 0) left else left + width - 24f, y0 + h + 12f, lab)
        }
        val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(42, 120, 214); strokeWidth = 1.6f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        for (i in 1 until pts.size) cv.drawLine(px(pts[i - 1].measuredAt), py(pts[i - 1].value), px(pts[i].measuredAt), py(pts[i].value), lineP)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(42, 120, 214) }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        if (pts.size <= 80) pts.forEach { cv.drawCircle(px(it.measuredAt), py(it.value), 3.6f, ring); cv.drawCircle(px(it.measuredAt), py(it.value), 2.4f, dot) }
    }
}
