package com.startinsnow.gpstracker.ui.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.startinsnow.gpstracker.R
import com.startinsnow.gpstracker.core.analysis.AnalysisPoint
import com.startinsnow.gpstracker.core.analysis.ColorMode
import com.startinsnow.gpstracker.core.analysis.RouteAnalysis
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** 分享圖片上的一格統計。 */
data class ShareStat(val value: String, val unit: String, val label: String)

/**
 * 把一筆活動畫成 1080×1350 的分享圖片（路線＋統計），版面與網站的「分享圖片」一致。
 * 只在本機繪製；沒有網路請求。路線可依速度／海拔上色（色階與地圖相同）。
 */
object ShareCardRenderer {
    private const val W = 1080
    private const val H = 1350

    fun render(
        context: Context,
        title: String,
        subtitle: String,
        points: List<AnalysisPoint>,
        colorMode: ColorMode,
        stats: List<ShareStat>
    ): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val bg = Paint().apply { shader = LinearGradient(0f, 0f, W.toFloat(), H.toFloat(), Color.parseColor("#070b14"), Color.parseColor("#10233f"), Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), bg)

        val text = Paint(Paint.ANTI_ALIAS_FLAG)
        fun fitSize(s: String, maxW: Float, start: Float, bold: Boolean): Float {
            var size = start
            text.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            text.textSize = size
            while (size > 30f && text.measureText(s) > maxW) { size -= 2f; text.textSize = size }
            return size
        }
        // 標題與副標
        text.color = Color.parseColor("#eaf0fb")
        text.textSize = fitSize(title, W - 120f, 68f, true)
        c.drawText(title, 60f, 130f, text)
        text.typeface = Typeface.DEFAULT
        text.color = Color.parseColor("#8c9ab3")
        text.textSize = 34f
        c.drawText(subtitle, 60f, 190f, text)

        // 路線方框
        val box = RectF(60f, 240f, W - 60f, 940f)
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E60F1828"); style = Paint.Style.FILL }
        c.drawRoundRect(box, 28f, 28f, boxPaint)
        boxPaint.apply { color = Color.parseColor("#2c3f63"); style = Paint.Style.STROKE; strokeWidth = 2f }
        c.drawRoundRect(box, 28f, 28f, boxPaint)

        val projected = project(points, box.width(), box.height(), 56f, 700)
        var legend: Triple<ColorMode, Double, Double>? = null
        if (projected != null) {
            val values = if (colorMode != ColorMode.NONE) RouteAnalysis.series(points, colorMode) else null
            val range = values?.let { RouteAnalysis.range(it) }
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
            c.save()
            c.translate(box.left, box.top)
            // 光暈
            val all = Path()
            projected.forEachIndexed { i, q -> if (i == 0) all.moveTo(q.x, q.y) else all.lineTo(q.x, q.y) }
            line.color = Color.parseColor("#4022d3ee"); line.strokeWidth = 22f
            c.drawPath(all, line)
            line.strokeWidth = 9f
            if (values != null && range != null) {
                for (i in 1 until projected.size) {
                    val a = projected[i - 1]
                    val b = projected[i]
                    val v = (values[a.index] + values[b.index]) / 2
                    line.color = Color.parseColor(RouteAnalysis.rampColor(colorMode, (v - range.first) / (range.second - range.first)))
                    c.drawLine(a.x, a.y, b.x, b.y, line)
                }
                legend = Triple(colorMode, range.first, range.second)
            } else {
                line.color = Color.parseColor("#22d3ee")
                c.drawPath(all, line)
            }
            fun dot(q: Pt, color: String) {
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.parseColor(color) }
                val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.parseColor("#04121f"); style = Paint.Style.STROKE; strokeWidth = 4f }
                c.drawCircle(q.x, q.y, 15f, fill); c.drawCircle(q.x, q.y, 15f, ring)
            }
            dot(projected.first(), "#34d399")
            dot(projected.last(), "#ff7a45")
            c.restore()
        } else {
            text.color = Color.parseColor("#8c9ab3"); text.textSize = 34f; text.textAlign = Paint.Align.CENTER
            c.drawText(context.getString(R.string.share_card_no_route), W / 2f, box.centerY(), text)
            text.textAlign = Paint.Align.LEFT
        }

        // 色階圖例
        legend?.let { (mode, lo, hi) ->
            val lx = box.left + 28f
            val ly = box.bottom - 52f
            val lw = 360f
            val stops = 11
            val colors = IntArray(stops) { Color.parseColor(RouteAnalysis.rampColor(mode, it / (stops - 1).toDouble())) }
            val barPaint = Paint().apply { shader = LinearGradient(lx, 0f, lx + lw, 0f, colors, null, Shader.TileMode.CLAMP) }
            c.drawRect(lx, ly, lx + lw, ly + 12f, barPaint)
            text.color = Color.parseColor("#8c9ab3"); text.textSize = 24f; text.typeface = Typeface.DEFAULT
            val unit = if (mode == ColorMode.SPEED) " km/h" else " m"
            c.drawText("${Math.round(lo)}$unit", lx, ly + 38f, text)
            text.textAlign = Paint.Align.RIGHT
            c.drawText("${Math.round(hi)}$unit", lx + lw, ly + 38f, text)
            text.textAlign = Paint.Align.LEFT
        }

        // 統計（最多 4 格，2×2）
        val cardW = (W - 120f - 24f) / 2f
        val cardH = 140f
        stats.take(4).forEachIndexed { n, st ->
            val cx = 60f + (n % 2) * (cardW + 24f)
            val cy = 976f + (n / 2) * (cardH + 20f)
            val r = RectF(cx, cy, cx + cardW, cy + cardH)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E60F1828") }
            c.drawRoundRect(r, 22f, 22f, p)
            p.apply { color = Color.parseColor("#1f2d47"); style = Paint.Style.STROKE; strokeWidth = 2f }
            c.drawRoundRect(r, 22f, 22f, p)
            text.typeface = Typeface.DEFAULT_BOLD; text.color = Color.parseColor("#eaf0fb"); text.textSize = 54f
            c.drawText(st.value, cx + 28f, cy + 70f, text)
            val vw = text.measureText(st.value)
            text.typeface = Typeface.DEFAULT; text.color = Color.parseColor("#8c9ab3")
            if (st.unit.isNotEmpty()) { text.textSize = 28f; c.drawText(st.unit, cx + 28f + vw + 10f, cy + 70f, text) }
            text.textSize = 26f
            c.drawText(st.label, cx + 28f, cy + 114f, text)
        }

        text.typeface = Typeface.DEFAULT_BOLD; text.color = Color.parseColor("#4c8dff"); text.textSize = 30f; text.textAlign = Paint.Align.RIGHT
        c.drawText(context.getString(R.string.share_card_footer), W - 60f, H - 36f, text)
        return bmp
    }

    private data class Pt(val x: Float, val y: Float, val index: Int)

    /** 等距圓柱投影（經度依緯度縮放，保持比例）到 w×h 的方框內；index 是原始點的索引。 */
    private fun project(points: List<AnalysisPoint>, w: Float, h: Float, padding: Float, maxPts: Int): List<Pt>? {
        val valid = points.withIndex().filter { it.value.latitude.isFinite() && it.value.longitude.isFinite() }
        if (valid.size < 2) return null
        val items = if (valid.size <= maxPts) valid else {
            val step = (valid.size - 1).toDouble() / (maxPts - 1)
            List(maxPts) { i -> valid[min(valid.size - 1, Math.round(i * step).toInt())] }
        }
        val minX = items.minOf { it.value.longitude }; val maxX = items.maxOf { it.value.longitude }
        val minY = items.minOf { it.value.latitude }; val maxY = items.maxOf { it.value.latitude }
        val k = cos(Math.toRadians((minY + maxY) / 2))
        val spanX = max((maxX - minX) * k, 1e-9)
        val spanY = max(maxY - minY, 1e-9)
        val scale = min((w - 2 * padding) / spanX, (h - 2 * padding) / spanY)
        val offX = (w - spanX * scale) / 2
        val offY = (h - spanY * scale) / 2
        return items.map {
            Pt(
                x = (offX + (it.value.longitude - minX) * k * scale).toFloat(),
                y = (h - (offY + (it.value.latitude - minY) * scale)).toFloat(),
                index = it.index
            )
        }
    }
}
