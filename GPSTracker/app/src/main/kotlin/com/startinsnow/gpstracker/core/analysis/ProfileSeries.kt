package com.startinsnow.gpstracker.core.analysis

/** 剖面圖（海拔／速度）用的純函式：不依賴 Compose，可以直接用 JVM 單元測試。 */
object ProfileSeries {
    /**
     * 把 [values] 縮成最多 [maxPoints] 個點：每一段取平均，保留整體起伏又不會畫上萬個點。
     * 點數本來就不超過 [maxPoints] 時原樣回傳。
     */
    fun bucketMean(values: DoubleArray, maxPoints: Int): DoubleArray {
        require(maxPoints >= 2)
        if (values.size <= maxPoints) return values
        return DoubleArray(maxPoints) { bucket ->
            val from = (bucket.toLong() * values.size / maxPoints).toInt()
            val to = (((bucket + 1).toLong() * values.size / maxPoints).toInt()).coerceAtLeast(from + 1)
            var sum = 0.0
            for (i in from until minOf(to, values.size)) sum += values[i]
            sum / (minOf(to, values.size) - from)
        }
    }

    /** 手指在圖表上的水平位置（0～1）對應到第幾個資料點。 */
    fun indexAt(fraction: Float, size: Int): Int {
        if (size <= 0) return 0
        return (fraction.coerceIn(0f, 1f) * (size - 1)).toInt().coerceIn(0, size - 1)
    }

    /** 把數值換算成圖上的高度比例（0＝最低、1＝最高）；全部相同時畫在中間。 */
    fun normalized(value: Double, low: Double, high: Double): Float =
        if (high - low < 1e-9) 0.5f else ((value - low) / (high - low)).toFloat().coerceIn(0f, 1f)
}
