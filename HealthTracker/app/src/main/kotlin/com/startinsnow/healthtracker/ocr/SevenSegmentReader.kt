package com.startinsnow.healthtracker.ocr

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 七段液晶數字讀取（血糖機螢幕）。一般文字辨識（ML Kit）讀不出這種字型，所以另外用「看哪幾段有墨」來判斷：
 * 1. 局部對比取出深色像素，只留長條狀、大小相近的連通塊（＝各個線段），丟掉反光與刮痕；
 * 2. 找傾斜角度把斜體字扶正，依空白欄位切成一個個數字；
 * 3. 每個數字檢查 a～g 七個位置有沒有墨，查表得到 0～9；窄的數字視為 1。
 * 純影像運算，不依賴 Android API，可直接做單元測試。
 */
object SevenSegmentReader {
    private val TABLE = mapOf(
        "abcdef" to 0, "bc" to 1, "abdeg" to 2, "abcdg" to 3, "bcfg" to 4,
        "acdfg" to 5, "acdefg" to 6, "abc" to 7, "abcdefg" to 8, "abcdfg" to 9,
    )

    /** gray：0..255 灰階，大小 w×h。回傳數字字串（1～4 位）；讀不出來回傳 null。 */
    fun read(gray: IntArray, w: Int, h: Int, digitHeight: Int = 0): String? {
        // 局部對比的視窗大小會影響結果：用三種大小各讀一次，至少兩次相同才採用，避免單次誤讀
        val results = listOf(0.12, 0.2, 0.3).mapNotNull { readWith(gray, w, h, it, digitHeight) }
        if (results.isEmpty()) return null
        val top = results.groupingBy { it }.eachCount().maxByOrNull { it.value }!!
        return if (top.value >= 2) top.key else null
    }

    internal fun readWith(gray: IntArray, w: Int, h: Int, windowFrac: Double, digitHeight: Int = 0): String? {
        if (w < 40 || h < 40) return null
        // 四周補一圈底色，避免緊貼邊緣的數字被當成邊界雜訊丟掉
        val pad = max(8, min(w, h) / 12)
        val pw = w + 2 * pad
        val ph = h + 2 * pad
        val mean = gray.average().toInt()
        val g = IntArray(pw * ph) { mean }
        for (y in 0 until h) System.arraycopy(gray, y * w, g, (y + pad) * pw + pad, w)

        // 局部背景的視窗要比線段結構大：已知數字高度時取 0.7～1.8 倍數字高度，否則取影像短邊的比例
        val windowPx = if (digitHeight > 0) (digitHeight * windowFrac * 6).toInt() else (min(pw, ph) * windowFrac).toInt().coerceIn(21, 151)
        val mask = darkMask(g, pw, ph, windowPx) ?: return null
        val segs = if (digitHeight > 0) hintedComponents(mask, pw, ph, digitHeight) else segmentComponents(mask, pw, ph)
        if (segs.size < 3) return null
        val pixels = digitBand(segs, pw, ph, digitHeight) ?: return null // 編碼 y*pw+x
        val k = bestShear(pixels, pw, ph)
        return decodeDigits(pixels, pw, ph, k)
    }

    // ---------- 1. 深色遮罩 ----------

    private fun darkMask(g: IntArray, w: Int, h: Int, windowPx: Int): BooleanArray? {
        val blur = boxBlur(g, w, h, 1)
        val win = windowPx.coerceAtLeast(21) / 2
        val bg = boxBlur(blur, w, h, win)
        val sorted = blur.sorted()
        val spread = sorted[(sorted.size * 0.9).toInt()] - sorted[(sorted.size * 0.1).toInt()]
        val t = (spread * 0.18).toInt().coerceIn(12, 40)
        val mask = BooleanArray(w * h) { bg[it] - blur[it] > t }
        return if (mask.count { it } < 200) null else mask
    }

    /** 以 integral image 做方框平均（半徑 r）。 */
    private fun boxBlur(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val ii = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0L
            for (x in 0 until w) {
                row += src[y * w + x]
                ii[(y + 1) * (w + 1) + x + 1] = ii[y * (w + 1) + x + 1] + row
            }
        }
        return IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val x0 = max(0, x - r)
            val y0 = max(0, y - r)
            val x1 = min(w, x + r + 1)
            val y1 = min(h, y + r + 1)
            val sum = ii[y1 * (w + 1) + x1] - ii[y0 * (w + 1) + x1] - ii[y1 * (w + 1) + x0] + ii[y0 * (w + 1) + x0]
            (sum / ((x1 - x0) * (y1 - y0))).toInt()
        }
    }

    // ---------- 2. 線段候選 ----------

    private class Comp(val pixels: IntArray, val area: Int, val bw: Int, val bh: Int, val y0: Int, val touchesEdge: Boolean)

    /** 已知數字高度時（由文字辨識的位置線索而來）：只看大小合理的塊，不必猜標準大小；黏在一起的線段也保留。 */
    private fun hintedComponents(mask: BooleanArray, w: Int, h: Int, digitHeight: Int): List<Comp> =
        allComponents(mask, w, h).filter {
            it.area >= 0.012 * digitHeight * digitHeight && !it.touchesEdge &&
                it.bh <= 1.3 * digitHeight && it.bw <= 3.0 * digitHeight && max(it.bw, it.bh) <= 12 * min(it.bw, it.bh)
        }

    private fun allComponents(mask: BooleanArray, w: Int, h: Int): List<Comp> {
        val seen = BooleanArray(mask.size)
        val comps = ArrayList<Comp>()
        val stack = IntArray(mask.size)
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            val pts = ArrayList<Int>()
            var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = -1; var y1 = -1
            while (sp > 0) {
                val p = stack[--sp]
                pts.add(p)
                val x = p % w
                val y = p / w
                x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val n = ny * w + nx
                    if (mask[n] && !seen[n]) { seen[n] = true; stack[sp++] = n }
                }
            }
            comps.add(Comp(pts.toIntArray(), pts.size, x1 - x0 + 1, y1 - y0 + 1, y0, x0 == 0 || y0 == 0 || x1 == w - 1 || y1 == h - 1))
        }
        return comps
    }

    private fun segmentComponents(mask: BooleanArray, w: Int, h: Int): List<Comp> {
        val comps = allComponents(mask, w, h)
        // 螢幕上各線段的大小相近：找「面積最集中的一群」（至少 4 塊）當標準，再依標準的長寬篩選
        val cand = comps.filter { !it.touchesEdge && it.area >= 60 }.sortedBy { it.area }
        var bestStart = 0
        var bestCount = 0
        var j = 0
        for (i in cand.indices) {
            if (j < i) j = i
            while (j + 1 < cand.size && cand[j + 1].area <= 1.8 * cand[i].area) j++
            if (j - i + 1 > bestCount) { bestCount = j - i + 1; bestStart = i }
        }
        if (bestCount < 4) return emptyList()
        val cluster = cand.subList(bestStart, bestStart + bestCount)
        val minArea = cluster.first().area
        val maxArea = cluster.last().area
        val medLong = cluster.map { max(it.bw, it.bh) }.sorted()[cluster.size / 2]
        val medShort = cluster.map { min(it.bw, it.bh) }.sorted()[cluster.size / 2]
        // 相鄰線段常黏在一起（L 形），所以短邊與面積上限放寬；邊框、機身輪廓這類又大又長的會被丟掉
        return cand.filter {
            it.area >= 0.4 * minArea && it.area <= 3.2 * maxArea &&
                max(it.bw, it.bh) <= 1.9 * medLong && min(it.bw, it.bh) <= 4 * medShort &&
                max(it.bw, it.bh) <= 8 * min(it.bw, it.bh) // 太細長的是底線或刮痕
        }
    }

    /**
     * 只留「一排數字」所在的高度範圍：直條線段的長度約是數字高度的 0.42，
     * 據此推出數字高度，再找墨最多的那一段高度；範圍外的條狀雜物（底線、單位字、反光）直接丟掉。
     */
    private fun digitBand(segs: List<Comp>, w: Int, h: Int, hintHeight: Int): IntArray? {
        val verticals = segs.filter { it.bh >= 1.8 * it.bw }.map { it.bh }.sorted()
        if (hintHeight <= 0 && verticals.size < 2) return null
        val digitH = if (hintHeight > 0) hintHeight else (verticals[verticals.size / 2] * 2.4).toInt()
        val rows = IntArray(h)
        for (c in segs) for (p in c.pixels) rows[p / w]++
        var best = -1
        var bestY = 0
        var sum = 0
        for (y in 0 until h) {
            sum += rows[y]
            if (y >= digitH) sum -= rows[y - digitH]
            if (sum > best) { best = sum; bestY = y - digitH + 1 }
        }
        // 線段的中心點落在這一排的高度範圍內就保留（整個線段保留，不切半）
        val kept = segs.filter { val mid = it.y0 + it.bh / 2; mid >= bestY && mid <= bestY + digitH }.flatMap { c -> c.pixels.asList() }
        return if (kept.size < 100) null else kept.toIntArray()
    }

    // ---------- 3. 扶正斜體 ----------

    private fun shearX(p: Int, w: Int, k: Double, midY: Double, off: Int): Int {
        val y = p / w
        val x = p % w
        return x + (-k * (y - midY)).roundToInt() + off
    }

    private fun bestShear(pixels: IntArray, w: Int, h: Int): Double {
        var best = -1.0
        var bestK = 0.0
        val off = h
        var step = -20
        while (step <= 20) {
            val k = step * 0.02
            val col = IntArray(w + 2 * h + 2)
            for (p in pixels) col[shearX(p, w, k, h / 2.0, off).coerceIn(0, col.size - 1)]++
            var score = 0.0
            for (c in col) score += c.toDouble() * c
            if (score > best) { best = score; bestK = k }
            step++
        }
        return bestK
    }

    // ---------- 4. 切數字、查表 ----------

    private fun decodeDigits(pixels: IntArray, w: Int, h: Int, k: Double): String? {
        val off = h
        val sw = w + 2 * h + 2
        val xs = IntArray(pixels.size) { shearX(pixels[it], w, k, h / 2.0, off).coerceIn(0, sw - 1) }
        val ys = IntArray(pixels.size) { pixels[it] / w }
        val rows = IntArray(h)
        for (y in ys) rows[y]++
        val rowMax = rows.max()
        val yTop = rows.indexOfFirst { it > rowMax * 0.08 }
        val yBot = rows.indexOfLast { it > rowMax * 0.08 }
        if (yTop < 0) return null
        val dh = yBot - yTop + 1
        if (dh < 20) return null

        val col = IntArray(sw)
        for (i in xs.indices) if (ys[i] in yTop..yBot) col[xs[i]]++
        val runs = ArrayList<IntArray>()
        val gap = max(2, (dh * 0.05).toInt())
        var start = -1
        var last = -1
        for (x in 0 until sw) {
            if (col[x] > dh * 0.08) {
                if (start < 0) start = x
                last = x
            } else if (start >= 0 && x - last > gap) {
                runs.add(intArrayOf(start, last)); start = -1
            }
        }
        if (start >= 0) runs.add(intArrayOf(start, last))
        // 由右邊算起，相鄰數字的間隔不會超過數字高度的 0.3 倍；更遠的是旁邊的雜物（框線、反光）。
        // 例外：「1」靠格子右側，它左邊的數字離得比較遠，所以右邊鄰居是「1」時放寬到 0.85 倍。
        val allRuns = runs.filter { it[1] - it[0] + 1 >= dh * 0.1 }
        val wideRun = allRuns.maxOfOrNull { it[1] - it[0] + 1 } ?: return null
        val chain = ArrayList<IntArray>()
        for (r in allRuns.reversed()) {
            val last = chain.lastOrNull()
            val lastNarrow = last != null && last[1] - last[0] + 1 < 0.55 * wideRun
            if (last == null || last[0] - r[1] <= dh * (if (lastNarrow) 0.85 else 0.3)) chain.add(r) else break
        }
        chain.reverse()
        val digits = if (chain.size > 3) chain.takeLast(3) else chain // 血糖最多 3 位數
        if (digits.isEmpty() || digits.size > 4) return null

        val wide = digits.maxOf { it[1] - it[0] + 1 }
        val sb = StringBuilder()
        for (d in digits) {
            val dw = d[1] - d[0] + 1
            if (dw < 0.55 * wide) { sb.append('1'); continue }
            val grid = BooleanArray(dh * dw)
            for (i in xs.indices) {
                val x = xs[i] - d[0]
                val y = ys[i] - yTop
                if (x in 0 until dw && y in 0 until dh) grid[y * dw + x] = true
            }
            fun ratio(ya: Double, yb: Double, xa: Double, xb: Double): Double {
                val y0 = (dh * ya).toInt()
                val y1 = max((dh * yb).toInt(), y0 + 1)
                val x0 = (dw * xa).toInt()
                val x1 = max((dw * xb).toInt(), x0 + 1)
                var on = 0
                var n = 0
                for (y in y0 until min(y1, dh)) for (x in x0 until min(x1, dw)) { n++; if (grid[y * dw + x]) on++ }
                return if (n == 0) 0.0 else on.toDouble() / n
            }
            val probes = mapOf(
                'a' to ratio(0.0, 0.16, 0.2, 0.8), 'g' to ratio(0.42, 0.58, 0.2, 0.8), 'd' to ratio(0.84, 1.0, 0.2, 0.8),
                'f' to ratio(0.12, 0.45, 0.0, 0.3), 'b' to ratio(0.12, 0.45, 0.7, 1.0),
                'e' to ratio(0.55, 0.88, 0.0, 0.3), 'c' to ratio(0.55, 0.88, 0.7, 1.0),
            )
            val on = probes.filter { it.value > 0.4 }.keys.sorted().joinToString("")
            sb.append(TABLE[on]?.toString() ?: return null)
        }
        return sb.toString()
    }

    /** 讀到的數字字串轉成血糖值；位數或範圍不合理回傳 null。 */
    fun toGlucose(digits: String?): Double? {
        val v = digits?.takeIf { it.length in 2..3 }?.toIntOrNull() ?: return null
        return v.toDouble().takeIf { it in GlucoseOcrParser.MIN_MGDL..GlucoseOcrParser.MAX_MGDL }
    }

}
