package com.startinsnow.gpstracker.ui.map

import android.content.Context
import com.startinsnow.gpstracker.export.TileProviderConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionDefinition
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition

sealed class OfflineDownloadProgress {
    data class InProgress(val percent: Int) : OfflineDownloadProgress()
    data object Completed : OfflineDownloadProgress()
    data class Failed(val reason: String) : OfflineDownloadProgress()
}

/**
 * 對應規格「41. Offline Map」。使用 MapLibre 內建的 OfflineManager 真正下載圖磚並存到本機資料庫，
 * 不是假裝下載；預設下載一個涵蓋台灣本島的固定範圍，供出國前預先準備離線地圖（規格 42 的跨國情境
 * 仍然可以之後再針對其他地區重複呼叫本方法擴充涵蓋範圍）。
 */
object OfflineMapManager {
    private val TAIWAN_BOUNDS = LatLngBounds.from(25.5, 122.1, 21.8, 119.9)

    fun downloadDefaultRegion(
        context: Context,
        tileProvider: TileProviderConfig = TileProviderConfig.DEFAULT_OSM,
        bounds: LatLngBounds = TAIWAN_BOUNDS,
        minZoom: Double = 0.0,
        maxZoom: Double = 10.0
    ): Flow<OfflineDownloadProgress> = callbackFlow {
        val styleJson = """{"version":8,"sources":{"base-tiles":{"type":"raster","tiles":["${tileProvider.tileUrlTemplate}"],"tileSize":256}},"layers":[{"id":"base-tiles-layer","type":"raster","source":"base-tiles"}]}"""
        val definition: OfflineRegionDefinition = OfflineTilePyramidRegionDefinition(
            styleJson,
            bounds,
            minZoom,
            maxZoom,
            context.resources.displayMetrics.density
        )
        val metadata = "gps_tracker_default_region".toByteArray()

        val offlineManager = OfflineManager.getInstance(context)
        offlineManager.createOfflineRegion(definition, metadata, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(status: OfflineRegionStatus) {
                        if (status.isComplete) {
                            trySend(OfflineDownloadProgress.Completed)
                            close()
                        } else if (status.requiredResourceCount > 0) {
                            val percent = (100.0 * status.completedResourceCount / status.requiredResourceCount).toInt()
                            trySend(OfflineDownloadProgress.InProgress(percent))
                        }
                    }

                    override fun onError(error: OfflineRegionError) {
                        trySend(OfflineDownloadProgress.Failed(error.message ?: "unknown offline map error"))
                        close()
                    }

                    override fun mapboxTileCountLimitExceeded(limit: Long) {
                        trySend(OfflineDownloadProgress.Failed("tile limit exceeded: $limit"))
                        close()
                    }
                })
                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
            }

            override fun onError(error: String) {
                trySend(OfflineDownloadProgress.Failed(error))
                close()
            }
        })

        awaitClose { }
    }
}
