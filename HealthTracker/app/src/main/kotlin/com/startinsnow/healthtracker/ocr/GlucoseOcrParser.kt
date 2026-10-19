package com.startinsnow.healthtracker.ocr

/** OCR 辨識出的一行文字與其高度（像素）。LCD 上最大的數字通常就是量測結果。 */
data class OcrLine(val text: String, val height: Int)

data class GlucoseCandidate(val value: Double, val score: Double, val lineText: String)

/**
 * 從血糖機照片的 OCR 結果挑出可能的血糖值（mg/dL）。
 * 七段顯示器辨識本來就不穩，所以只產生「候選」，最後一定由使用者確認。
 */
object GlucoseOcrParser {
    const val MIN_MGDL = 20.0
    const val MAX_MGDL = 800.0

    // 2～3 位整數；前後不能緊鄰數字或日期／時間分隔符（排除 10/19、09:27、2026、1.5 等）。
    private val number = Regex("""(?<![\d:/.\-])(\d{2,3})(?![\d:/.\-])""")
    private val unitHint = Regex("""mg\s*/?\s*d\s*l|mg/dl""", RegexOption.IGNORE_CASE)

    /** 七段顯示器常被讀成字母：在含數字的 2～3 字元片段內，把 O→0、I/l→1 修回來。 */
    internal fun fixDigitLookalikes(text: String): String =
        Regex("""[0-9OoIl]{2,3}""").replace(text) { m ->
            val t = m.value
            if (t.any { it.isDigit() }) t.replace('O', '0').replace('o', '0').replace('I', '1').replace('l', '1') else t
        }

    fun candidates(lines: List<OcrLine>): List<GlucoseCandidate> {
        if (lines.isEmpty()) return emptyList()
        val maxHeight = lines.maxOf { it.height }.coerceAtLeast(1)
        val best = LinkedHashMap<Double, GlucoseCandidate>()
        lines.forEachIndexed { i, line ->
            val text = fixDigitLookalikes(line.text)
            val nearUnit = listOf(i - 1, i, i + 1).any { j ->
                lines.getOrNull(j)?.let { unitHint.containsMatchIn(it.text) } == true
            }
            number.findAll(text).forEach { m ->
                val v = m.groupValues[1].toDouble()
                if (v < MIN_MGDL || v > MAX_MGDL) return@forEach
                val score = line.height.toDouble() / maxHeight + if (nearUnit) 0.5 else 0.0
                val prev = best[v]
                if (prev == null || score > prev.score) best[v] = GlucoseCandidate(v, score, line.text)
            }
        }
        return best.values.sortedByDescending { it.score }
    }
}
