package com.startinsnow.healthtracker.ocr

import kotlin.math.max
import kotlin.math.min

/**
 * 整張照片 → 血糖數字：先用 DigitLocator 找出數字區再讀；找不到或讀不出來，就掃上半部的 12 個重疊視窗，
 * 讀到的結果以「多數相同」為準（只有一個視窗讀到就採用它）。純影像運算，不依賴 Android，可直接單元測試。
 */
object DigitSearch {
    private const val WORK_SIDE = 900

    /** gray：0..255 灰階，w×h。回傳數字字串（2～3 位，且是合理血糖值）；讀不到回傳 null。 */
    fun search(gray: IntArray, w: Int, h: Int): String? {
        val scale = WORK_SIDE.toDouble() / max(w, h)
        val w9 = max(1, (w * scale).toInt())
        val h9 = max(1, (h * scale).toInt())
        val small = if (scale >= 1.0) gray else shrink(gray, w, h, w9, h9)
        val sw = if (scale >= 1.0) w else w9
        val sh = if (scale >= 1.0) h else h9
        val f = w.toDouble() / sw

        // A：先把數字區找出來，再用原解析度裁切來讀（準確度最高）
        DigitLocator.locate(small, sw, sh)?.let { r ->
            val x0 = (r.x0 * f).toInt().coerceIn(0, w - 2)
            val x1 = (r.x1 * f).toInt().coerceIn(x0 + 1, w)
            val y0 = ((r.y0 - r.ref) * f).toInt().coerceIn(0, h - 2)
            val y1 = ((r.y1 + r.ref) * f).toInt().coerceIn(y0 + 1, h)
            val roi = crop(gray, w, x0, y0, x1, y1)
            val read = SevenSegmentReader.read(roi, x1 - x0, y1 - y0)
            if (SevenSegmentReader.toGlucose(read) != null) return read
            // 線段黏在一起時（光線不均、反光），改用「已知數字高度」模式再讀；高度約為直條長度的 2.35 倍
            for (mul in listOf(1.0, 0.9, 1.1)) {
                val hinted = SevenSegmentReader.read(roi, x1 - x0, y1 - y0, (r.ref * f * 2.35 * mul).toInt())
                if (SevenSegmentReader.toGlucose(hinted) != null) return hinted
            }
        }

        // C：掃描上半部的重疊視窗，多數決
        val ww = (sw * 0.7).toInt()
        val wh = (sh * 0.42).toInt()
        val reads = ArrayList<String>()
        for (yi in 0..3) {
            val y0 = ((sh - wh) * yi / 3.0 * 0.75).toInt()
            for (xi in 0..2) {
                val x0 = ((sw - ww) * xi / 2.0).toInt()
                val read = SevenSegmentReader.read(crop(small, sw, x0, y0, x0 + ww, y0 + wh), ww, wh)
                if (SevenSegmentReader.toGlucose(read) != null) reads += read!!
            }
        }
        if (reads.isEmpty()) return null
        val counts = reads.groupingBy { it }.eachCount()
        return reads.firstOrNull { counts.getValue(it) >= 2 } ?: reads.first()
    }

    private fun crop(src: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int): IntArray {
        val cw = x1 - x0
        val ch = y1 - y0
        val out = IntArray(cw * ch)
        for (y in 0 until ch) System.arraycopy(src, (y0 + y) * w + x0, out, y * cw, cw)
        return out
    }

    /** 以區塊平均縮小。 */
    private fun shrink(src: IntArray, w: Int, h: Int, nw: Int, nh: Int): IntArray {
        val out = IntArray(nw * nh)
        for (y in 0 until nh) {
            val ya = y * h / nh
            val yb = max(ya + 1, min(h, (y + 1) * h / nh))
            for (x in 0 until nw) {
                val xa = x * w / nw
                val xb = max(xa + 1, min(w, (x + 1) * w / nw))
                var sum = 0L
                for (yy in ya until yb) for (xx in xa until xb) sum += src[yy * w + xx]
                out[y * nw + x] = (sum / ((yb - ya) * (xb - xa))).toInt()
            }
        }
        return out
    }
}
