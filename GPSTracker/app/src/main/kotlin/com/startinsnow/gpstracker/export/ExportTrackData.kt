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
    }
}
