package com.startinsnow.healthtracker.ocr

import kotlin.math.max
import kotlin.math.min

/**
 * 在整張血糖機照片裡找出「那一排七段數字」的位置，不需要文字辨識給線索。
 * 做法：取出深色線段（長條形的連通塊），找一組長度相近、上下兩層、左右連成一串的直條線段，
 * 它們的外框就是數字區。以 900 像素左右的縮圖計算（數字約 70 像素高）。
 */
object DigitLocator {
    class Roi(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val ref: Int)

    private class Bar(val x: Int, val y: Int, val w: Int, val h: Int) {
        val cx get() = x + w / 2.0
        val cy get() = y + h / 2.0
        val vert get() = h >= w
    }

    private fun bars(gray: IntArray, w: Int, h: Int): List<Bar> {
        val blur = SevenSegmentReader.boxBlur(gray, w, h, 1)
        val bg = SevenSegmentReader.boxBlur(blur, w, h, 30)
        val sorted = blur.sorted()
        val spread = sorted[(sorted.size * 0.9).toInt()] - sorted[(sorted.size * 0.1).toInt()]
        val t = (spread * 0.18).toInt().coerceIn(12, 40)
        val mask = BooleanArray(w * h) { bg[it] - blur[it] > t }
        val out = ArrayList<Bar>()
        for (c in SevenSegmentReader.allComponents(mask, w, h)) {
            if (c.area < 40 || c.touchesEdge) continue
            val lo = max(c.bw, c.bh).toDouble()
            val sh = min(c.bw, c.bh).toDouble()
            if (lo / sh < 1.8 || lo / sh > 9) continue
            if (c.area.toDouble() / (c.bw * c.bh) < 0.45) continue // 斜條的外框填充率偏低，但至少要有這個程度
            out += Bar(c.x0, c.y0, c.bw, c.bh)
        }
        return out
    }

    /** 回傳數字區（含左右各多留一個數字寬、上下各半個線段長），座標在傳入影像的尺度；找不到回傳 null。 */
    fun locate(gray: IntArray, w: Int, h: Int): Roi? {
        val verts = bars(gray, w, h).filter { it.vert }
        if (verts.size < 3) return null
        var bestScore = -1.0
        var bestChain: List<Bar>? = null
        var bestRef = 0
        for (ref in verts.map { it.h }.distinct().sorted()) {
            val grp = verts.filter { it.h >= 0.82 * ref && it.h <= 1.22 * ref }
            if (grp.size < 3) continue
            // 墨最密的一段高度窗（上下兩層直條）
            var inw: List<Bar> = emptyList()
            for (y0 in grp.map { it.cy }.sorted()) {
                val cand = grp.filter { it.cy >= y0 - 0.2 * ref && it.cy <= y0 + 1.5 * ref }
                if (cand.size > inw.size) inw = cand
            }
            if (inw.size < 3) continue
            val mid = (inw.minOf { it.cy } + inw.maxOf { it.cy }) / 2
            if (inw.count { it.cy < mid - 0.15 * ref } < 1 || inw.count { it.cy > mid + 0.15 * ref } < 1) continue
            // 左右要連成一串（「1」靠格子右側，兩個相鄰的「1」相距約 1.7 倍線段長，所以間隔上限放到 2 倍）
            val sorted = inw.sortedBy { it.cx }
            val chains = ArrayList<MutableList<Bar>>().apply { add(mutableListOf(sorted[0])) }
            for (v in sorted.drop(1)) {
                if (v.cx - chains.last().last().cx <= 2.0 * ref) chains.last().add(v) else chains.add(mutableListOf(v))
            }
            val chain = chains.maxByOrNull { it.size }!!
            if (chain.size < 3) continue
            val score = chain.size * (1 + 0.02 * ref)
            if (score > bestScore) { bestScore = score; bestChain = chain; bestRef = ref }
        }
        val ch = bestChain ?: return null
        val xp = (1.5 * bestRef).toInt()
        val yp = (0.5 * bestRef).toInt()
        return Roi(
            max(0, ch.minOf { it.x } - xp), max(0, ch.minOf { it.y } - yp),
            min(w, ch.maxOf { it.x + it.w } + xp), min(h, ch.maxOf { it.y + it.h } + yp), bestRef,
        )
    }
}
