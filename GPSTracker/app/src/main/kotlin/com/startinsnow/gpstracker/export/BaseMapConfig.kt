package com.startinsnow.gpstracker.export

/**
 * 地圖底圖選項。簡易地圖走 OpenFreeMap 的向量樣式（載入快、省流量，App 預設），
 * 街道圖與衛星圖則沿用原本的圖片圖磚來源。
 *
 * 這個檔案刻意不依賴 Android API，讓預設值、儲存字串與樣式網址可以在 JVM 單元測試驗證。
 */
enum class BaseMapType(
    val displayName: String,
    /** 給地圖／設定畫面顯示的純文字來源標註（不含 HTML）。 */
    val attributionText: String,
    val maxZoom: Int,
    /** 向量底圖的 MapLibre style 網址；圖片圖磚底圖為 null。 */
    val vectorStyleUrl: String?,
    /** 圖片圖磚底圖的圖磚設定；向量底圖為 null。 */
    val tileProvider: TileProviderConfig?
) {
    SIMPLE(
        displayName = "簡易地圖",
        attributionText = "© OpenFreeMap、© OpenMapTiles、資料來自 OpenStreetMap 貢獻者",
        maxZoom = 20,
        vectorStyleUrl = "https://tiles.openfreemap.org/styles/positron",
        tileProvider = null
    ),
    STREETS(
        displayName = "街道圖",
        attributionText = "© OpenStreetMap 貢獻者",
        maxZoom = TileProviderConfig.DEFAULT_OSM.maxZoom,
        vectorStyleUrl = null,
        tileProvider = TileProviderConfig.DEFAULT_OSM
    ),
    SATELLITE(
        displayName = "衛星",
        attributionText = "影像 © Esri、Maxar、Earthstar Geographics 與 GIS User Community",
        maxZoom = TileProviderConfig.FREE_ESRI_SATELLITE.maxZoom,
        vectorStyleUrl = null,
        tileProvider = TileProviderConfig.FREE_ESRI_SATELLITE
    );

    val isVector: Boolean get() = vectorStyleUrl != null

    /** 寫入 SettingsRepository 的字串（刻意使用列舉名稱，版本之間較穩定）。 */
    val storedValue: String get() = name

    companion object {
        /** App 預設底圖：向量簡易地圖。 */
        val DEFAULT = SIMPLE

        /** 讀取設定；空白、舊版或無法辨識的值一律回到預設的簡易地圖。 */
        fun fromStoredValue(value: String?): BaseMapType =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DEFAULT
    }
}

/** 依底圖類型產生 MapLibre 樣式。圖片圖磚沿用原本的 raster style JSON 組法。 */
object BaseMapStyles {
    const val OPEN_FREE_MAP_POSITRON_URL = "https://tiles.openfreemap.org/styles/positron"

    /**
     * 圖片圖磚底圖的 style JSON；[terrain] 不為 null 時會加上 raster-dem 與 3D 地形。
     * 向量底圖沒有圖片圖磚，回傳 null（由 MapLibre 直接載入 [BaseMapType.vectorStyleUrl]）。
     */
    fun rasterStyleJson(baseMapType: BaseMapType, terrain: TerrainProviderConfig?): String? {
        val tileProvider = baseMapType.tileProvider ?: return null
        val attribution = tileProvider.attributionHtml.replace("\"", "\\\"")
        val sources = StringBuilder()
        sources.append(
            """"base-tiles": { "type": "raster", "tiles": ["${tileProvider.tileUrlTemplate}"], "tileSize": 256, "maxzoom": ${tileProvider.maxZoom}, "attribution": "$attribution" }"""
        )
        val layers = StringBuilder()
        layers.append("""{ "id": "base-tiles-layer", "type": "raster", "source": "base-tiles" }""")

        var terrainBlock = ""
        if (terrain != null) {
            val terrainAttribution = terrain.attributionHtml.replace("\"", "\\\"")
            sources.append(
                """, "terrain-dem": { "type": "raster-dem", "tiles": ["${terrain.demUrlTemplate}"], "encoding": "${terrain.encoding}", "tileSize": 256, "attribution": "$terrainAttribution" }"""
            )
            // 山體陰影讓 3D 地形更有立體感。
            layers.append(""", { "id": "hillshade-layer", "type": "hillshade", "source": "terrain-dem" }""")
            terrainBlock = """, "terrain": { "source": "terrain-dem", "exaggeration": 1.3 }"""
        }

        return """
        {
          "version": 8,
          "sources": { $sources },
          "layers": [ $layers ]$terrainBlock
        }
        """.trimIndent()
    }
}
