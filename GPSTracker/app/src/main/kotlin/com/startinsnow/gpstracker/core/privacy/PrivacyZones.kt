package com.startinsnow.gpstracker.core.privacy

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 隱私區域：以某個位置為中心、半徑 [radiusMeters] 公尺的圓（例如「家」）。 */
data class PrivacyZone(val latitude: Double, val longitude: Double, val radiusMeters: Int)

/**
 * 隱私區域的純邏輯。上傳到網頁儀表板（公開的 GitHub 倉庫）與「分享圖片」時，
 * 軌跡開頭與結尾落在隱私區域內的連續點會被移除，看不出家（或工作地點）的確切位置；
 * 中途經過隱私區域的部分不動（與 Strava 的作法相同）。本機資料庫與本機匯出永遠是完整的。
 */
object PrivacyZones {
    const val MIN_RADIUS = 50
    const val MAX_RADIUS = 2000
    const val DEFAULT_RADIUS = 200
    const val MAX_ZONES = 5

    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    fun isInside(lat: Double, lon: Double, zones: List<PrivacyZone>): Boolean =
        zones.any { distanceMeters(lat, lon, it.latitude, it.longitude) <= it.radiusMeters }

    /** 移除開頭與結尾落在任一隱私區域內的連續點；全部都在區域內就回傳空清單。沒有區域時原樣回傳。 */
    fun <T> trimEnds(points: List<T>, zones: List<PrivacyZone>, lat: (T) -> Double, lon: (T) -> Double): List<T> {
        if (zones.isEmpty() || points.isEmpty()) return points
        val first = points.indexOfFirst { !isInside(lat(it), lon(it), zones) }
        if (first < 0) return emptyList()
        val last = points.indexOfLast { !isInside(lat(it), lon(it), zones) }
        return points.subList(first, last + 1)
    }

    fun clampRadius(radius: Int): Int = radius.coerceIn(MIN_RADIUS, MAX_RADIUS)

    /** 新增一個區域；超過 [MAX_ZONES] 個時回傳原清單。 */
    fun add(zones: List<PrivacyZone>, zone: PrivacyZone): List<PrivacyZone> =
        if (zones.size >= MAX_ZONES) zones else zones + zone.copy(radiusMeters = clampRadius(zone.radiusMeters))

    fun remove(zones: List<PrivacyZone>, index: Int): List<PrivacyZone> =
        if (index in zones.indices) zones.filterIndexed { i, _ -> i != index } else zones

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun toJson(zones: List<PrivacyZone>): String =
        json.encodeToString(ListSerializer(ZoneDto.serializer()), zones.map { ZoneDto(it.latitude, it.longitude, it.radiusMeters) })

    /** 寬鬆解析：壞掉的內容回傳空清單、缺欄位或座標不合理的項目略過，不會讓 App 當掉。 */
    fun fromJson(text: String?): List<PrivacyZone> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(ZoneDto.serializer()), text).mapNotNull { d ->
                val lat = d.lat
                val lon = d.lon
                if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) null
                else PrivacyZone(lat, lon, clampRadius(d.r ?: DEFAULT_RADIUS))
            }.take(MAX_ZONES)
        }.getOrDefault(emptyList())
    }
}

@Serializable
private data class ZoneDto(val lat: Double? = null, val lon: Double? = null, val r: Int? = null)
