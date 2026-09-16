package com.startinsnow.gpstracker.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 對應規格「52. JSON」：保存完整原始資料，並保留未來擴充能力（額外欄位不影響現有 Reader）。
 */
object JsonExporter {
    private val prettyJson = Json { prettyPrint = true }

    fun generate(data: ExportTrackData): String {
        val root = buildJsonObject {
            put("trackId", data.track.trackId)
            put("startTime", data.track.startTimeMs)
            put("endTime", data.track.endTimeMs?.let { JsonPrimitive(it) } ?: JsonNull)
            put("status", data.track.status.name)
            put("distanceMeters", data.track.distanceMeters)
            put("durationMs", data.track.durationMs)
            put("avgSpeedMps", data.track.avgSpeedMps)
            put("maxSpeedMps", data.track.maxSpeedMps)
            put("stepCount", data.track.stepCount)
            put("dominantMode", data.track.dominantMode.name)
            put("maxAltitudeMeters", data.track.maxAltitudeMeters?.let { JsonPrimitive(it) } ?: JsonNull)
            put("minAltitudeMeters", data.track.minAltitudeMeters?.let { JsonPrimitive(it) } ?: JsonNull)
            put("totalAscentMeters", data.track.totalAscentMeters)
            put("totalDescentMeters", data.track.totalDescentMeters)
            put("points", buildJsonArray {
                data.points.sortedBy { it.timestampMs }.forEach { p ->
                    add(buildJsonObject {
                        put("lat", p.latitude)
                        put("lon", p.longitude)
                        put("timestamp", p.timestampMs)
                        put("speed", p.speedMps?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("altitude", p.altitudeMeters?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("accuracy", p.accuracyMeters)
                        put("provider", p.provider)
                        put("reliability", p.reliability)
                        put("quality", p.quality)
                        put("mode", p.movementMode.name)
                    })
                }
            })
            put("photos", buildJsonArray {
                data.photos.forEach { photo ->
                    add(buildJsonObject {
                        put("photoId", photo.photoId)
                        put("type", photo.type.name)
                        put("timestamp", photo.timestampMs)
                        put("lat", photo.latitude?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("lon", photo.longitude?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("accuracy", photo.accuracyMeters?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("fileName", photo.filePath.substringAfterLast('/'))
                    })
                }
            })
            put("modes", buildJsonArray {
                data.segments.sortedBy { it.startTimeMs }.forEach { seg ->
                    add(buildJsonObject {
                        put("mode", seg.mode.name)
                        put("startTime", seg.startTimeMs)
                        put("endTime", seg.endTimeMs?.let { JsonPrimitive(it) } ?: JsonNull)
                    })
                }
            })
            put("gpsOutages", buildJsonArray {
                data.outages.forEach { outage ->
                    add(buildJsonObject {
                        put("startTime", outage.startTimestampMs)
                        put("endTime", outage.endTimestampMs?.let { JsonPrimitive(it) } ?: JsonNull)
                    })
                }
            })
            put("statistics", buildJsonObject {
                put("gpsPointCount", data.track.gpsPointCount)
                put("photoCount", data.track.photoCount)
                put("modeDistribution", runCatching {
                    prettyJson.parseToJsonElement(data.track.modeDistributionJson)
                }.getOrDefault(JsonObject(emptyMap())))
            })
            // schemaVersion 讓未來格式調整時，讀取端可以判斷相容性，符合「JSON 必須保留未來擴充能力」。
            put("schemaVersion", 1)
        }
        return prettyJson.encodeToString(JsonObject.serializer(), root)
    }
}
