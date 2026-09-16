package com.startinsnow.gpstracker.core.geo

/**
 * 地圖繪製用的軌跡點縮減（純邏輯，方便 Unit Test）。
 *
 * 目的：長時間記錄（例如 8 小時、上萬點）時，把整條路線丟給 MapLibre / Leaflet 會造成
 * 大量字串組裝與 native 繪製負擔。這裡用等距取樣把點數壓到上限內，
 * 但**永遠保留第一個與最後一個點**，確保路線頭尾不變。
 *
 * 注意：只影響「地圖顯示」，不影響資料庫、GPX / JSON / CSV 匯出（匯出永遠使用完整資料）。
 */
object RouteSimplifier {

    /** 預設地圖顯示上限，超過就縮減。 */
    const val DEFAULT_MAX_POINTS = 4000

    fun simplify(
        coordinates: List<Pair<Double, Double>>,
        maxPoints: Int = DEFAULT_MAX_POINTS
    ): List<Pair<Double, Double>> {
        require(maxPoints >= 2) { "maxPoints must be >= 2" }
        if (coordinates.size <= maxPoints) return coordinates

        val step = (coordinates.size - 1).toDouble() / (maxPoints - 1)
        val result = ArrayList<Pair<Double, Double>>(maxPoints)
        for (i in 0 until maxPoints) {
            val index = Math.round(i * step).toInt().coerceIn(0, coordinates.size - 1)
            val candidate = coordinates[index]
            if (result.isEmpty() || result.last() != candidate) {
                result += candidate
            }
        }
        if (result.last() != coordinates.last()) {
            result += coordinates.last()
        }
        return result
    }
}
