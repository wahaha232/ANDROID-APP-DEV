package com.startinsnow.gpstracker.export

import com.startinsnow.gpstracker.data.db.GpsOutageEntity
import com.startinsnow.gpstracker.data.db.MovementSegmentEntity
import com.startinsnow.gpstracker.data.db.PhotoEntity
import com.startinsnow.gpstracker.data.db.TrackEntity
import com.startinsnow.gpstracker.data.db.TrackPointEntity

/** 匯出用的完整 Track 資料包，GPX/JSON/CSV/HTML 匯出器共用同一份輸入，避免各自重複查詢資料庫。 */
data class ExportTrackData(
    val track: TrackEntity,
    val points: List<TrackPointEntity>,
    val photos: List<PhotoEntity>,
    val segments: List<MovementSegmentEntity>,
    val outages: List<GpsOutageEntity>
)

/**
 * 地圖圖磚來源設定（規格 3）：核心 GPS 邏輯完全不依賴這個設定，
 * 只有 Browser HTML 產生器與 Android 地圖畫面會讀取，方便未來替換成自架或商用 Tile Provider。
 */
data class TileProviderConfig(
    val name: String,
    val tileUrlTemplate: String,
    val attributionHtml: String,
    val maxZoom: Int = 19
) {
    companion object {
        /**
         * 預設值使用 OpenStreetMap 標準圖磚，僅供開發/個人低流量使用。
         * 依 OSM Tile Usage Policy，正式或高流量情境務必改用自架、Maptiler、Stadia Maps
         * 等有授權的 Tile Provider，並在 [com.startinsnow.gpstracker.data.prefs.SettingsRepository] 中調整。
         */
        val DEFAULT_OSM = TileProviderConfig(
            name = "OpenStreetMap (開發用，正式使用請更換)",
            tileUrlTemplate = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
            attributionHtml = "&copy; <a href=\"https://www.openstreetmap.org/copyright\">OpenStreetMap</a> contributors"
        )

        /**
         * 全部 0 元、免註冊、免 API key 的圖層。
         * 注意：OSM / OpenTopoMap 為社群免費資源，依其使用政策僅適合個人低流量；
         * 高流量或商用請自架圖磚（仍是 0 元授權，只是需自備伺服器）。
         */
        val FREE_OSM = DEFAULT_OSM

        val FREE_OPENTOPOMAP = TileProviderConfig(
            name = "OpenTopoMap (免費地形圖)",
            tileUrlTemplate = "https://a.tile.opentopomap.org/{z}/{x}/{y}.png",
            attributionHtml = "Map data: &copy; OpenStreetMap contributors, SRTM | Map style: &copy; <a href=\"https://opentopomap.org\">OpenTopoMap</a> (CC-BY-SA)",
            maxZoom = 17
        )

        /** Esri World Imagery：免金鑰、需標註的衛星影像（注意其服務條款）。 */
        val FREE_ESRI_SATELLITE = TileProviderConfig(
            name = "Esri World Imagery (免費衛星)",
            tileUrlTemplate = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
            attributionHtml = "Imagery &copy; Esri, Maxar, Earthstar Geographics, and the GIS User Community",
            maxZoom = 19
        )

        /** Sentinel-2 Cloudless（EOX）：免費、免金鑰的無雲衛星影像。 */
        val FREE_SENTINEL2_CLOUDLESS = TileProviderConfig(
            name = "Sentinel-2 Cloudless (免費衛星)",
            tileUrlTemplate = "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2020_3857/default/g/{z}/{y}/{x}.jpg",
            attributionHtml = "Sentinel-2 cloudless &copy; EOX (CC BY-NC-SA 4.0)",
            maxZoom = 14
        )

        /** 預設可直接選用的免費圖層清單（全部 0 元、無金鑰）。 */
        val FREE_PROVIDERS = listOf(
            FREE_OSM,
            FREE_OPENTOPOMAP,
            FREE_ESRI_SATELLITE,
            FREE_SENTINEL2_CLOUDLESS
        )
    }
}

/**
 * 3D 地形用的數位高程圖（DEM）來源設定。全免費、免 API key，
 * 讓 MapLibre 可以把地表立體化並加上山體陰影（hillshade），做出「3D 飛越」效果。
 */
data class TerrainProviderConfig(
    val name: String,
    val demUrlTemplate: String,
    /** MapLibre raster-dem 的編碼方式：terrarium 或 mapbox。 */
    val encoding: String,
    val attributionHtml: String,
    val maxZoom: Int = 15
) {
    companion object {
        /** AWS Terrain Tiles（Mapzen 開放資料集），免金鑰、可自由使用。 */
        val AWS_TERRARIUM = TerrainProviderConfig(
            name = "AWS Terrain Tiles (Terrarium)",
            demUrlTemplate = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png",
            encoding = "terrarium",
            attributionHtml = "Terrain: Mapzen / AWS Open Data"
        )
    }
}
