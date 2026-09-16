package com.startinsnow.gpstracker.ui.map

import android.content.Context
import com.startinsnow.gpstracker.export.TileProviderConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.ProducerScope
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
 *
 * 重要：呼叫前會先列出既有離線區域，若同一份 metadata 的區域已存在就直接沿用（不重複建立、
 * 不重複下載），只有在真的沒有時才建立新區域。
 */
object OfflineMapManager {
    private val TAIWAN_BOUNDS = LatLngBounds.from(25.5, 122.1, 21.8, 119.9)
    private const val METADATA = "gps_tracker_default_region"

    fun downloadDefaultRegion(
        context: Context,
        tileProvider: TileProviderConfig = TileProviderConfig.DEFAULT_OSM,
        bounds: LatLngBounds = TAIWAN_BOUNDS,
        minZoom: Double = 0.0,
        maxZoom: Double = 10.0
    ): Flow<OfflineDownloadProgress> = callbackFlow {
        val styleJson =
            """{"version":8,"sources":{"base-tiles":{"type":"raster","tiles":["${tileProvider.tileUrlTemplate}"],"tileSize":256}},"layers":[{"id":"base-tiles-layer","type":"raster","source":"base-tiles"}]}"""
        val definition: OfflineRegionDefinition = OfflineTilePyramidRegionDefinition(
            styleJson,
            bounds,
            minZoom,
            maxZoom,
            context.resources.displayMetrics.density
        )
        val metadata = METADATA.toByteArray()
        val offlineManager = OfflineManager.getInstance(context)
        val submitted = AtomicBoolean(false)

        fun send(progress: OfflineDownloadProgress) {
            // callbackFlow 的 channel 可能已關閉（畫面離開），不可讓 trySend 的失敗變成例外。
            runCatching { trySend(progress) }
        }

        fun observeRegion(region: OfflineRegion) {
            region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                override fun onStatusChanged(status: OfflineRegionStatus) {
                    if (status.isComplete) {
                        send(OfflineDownloadProgress.Completed)
                    } else if (status.requiredResourceCount > 0) {
                        val percent = (100.0 * status.completedResourceCount / status.requiredResourceCount).toInt()
                        send(OfflineDownloadProgress.InProgress(percent))
                    }
                }

                override fun onError(error: OfflineRegionError) {
                    send(OfflineDownloadProgress.Failed(error.message ?: "unknown offline map error"))
                }

                override fun mapboxTileCountLimitExceeded(limit: Long) {
                    send(OfflineDownloadProgress.Failed("tile limit exceeded: $limit"))
                }
            })
            // 已下載完成的區域不會再收到 onStatusChanged，這裡主動查一次狀態，
            // 才不會出現「按了下載卻一直停在 0%」的情形。
            region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                override fun onStatus(status: OfflineRegionStatus?) {
                    if (status == null) return
                    if (status.isComplete) {
                        send(OfflineDownloadProgress.Completed)
                    } else {
                        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                        val percent = if (status.requiredResourceCount > 0) {
                            (100.0 * status.completedResourceCount / status.requiredResourceCount).toInt()
                        } else {
                            0
                        }
                        send(OfflineDownloadProgress.InProgress(percent))
                    }
                }

                override fun onError(error: String?) {
                    send(OfflineDownloadProgress.Failed(error ?: "unknown offline map error"))
                }
            })
        }

        fun createRegion() {
            if (!submitted.compareAndSet(false, true)) return
            offlineManager.createOfflineRegion(
                definition,
                metadata,
                object : OfflineManager.CreateOfflineRegionCallback {
                    override fun onCreate(offlineRegion: OfflineRegion) {
                        observeRegion(offlineRegion)
                    }

                    override fun onError(error: String) {
                        send(OfflineDownloadProgress.Failed(error))
                    }
                }
            )
        }

        offlineManager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val existing = offlineRegions?.firstOrNull { region ->
                    runCatching { region.metadata.contentEquals(metadata) }.getOrDefault(false)
                }
                if (existing != null) {
                    observeRegion(existing)
                    submitted.set(true)
                } else {
                    createRegion()
                }
            }

            override fun onError(error: String) {
                // 無法列出既有區域時仍要能建立（不因為這個錯誤就讓功能失效）。
                createRegion()
            }
        })

        awaitClose { }
    }
}
