package com.startinsnow.gpstracker.core.geo

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 純數學運算，不依賴任何 Android API，方便在 JVM Unit Test 直接驗證。
 */
object GeoMath {

    private const val EARTH_RADIUS_METERS = 6371000.0

    /** Haversine 公式計算兩點間距離（公尺）。 */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    /** 兩點間方位角（0..360，正北為 0）。 */
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaLambda = Math.toRadians(lon2 - lon1)
        val y = sin(deltaLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
        val theta = atan2(y, x)
        return (Math.toDegrees(theta) + 360.0) % 360.0
    }

    /** 依 bearing 換算 8 方位羅盤字串（N/NE/E/SE/S/SW/W/NW）。 */
    fun compassDirection(bearingDegrees: Double): String {
        val directions = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val normalized = ((bearingDegrees % 360.0) + 360.0) % 360.0
        val index = ((normalized + 22.5) / 45.0).toInt() % 8
        return directions[index]
    }

    /** 由距離（公尺）與時間差（秒）換算速度（m/s），時間差 <= 0 時回傳 0。 */
    fun speedMetersPerSecond(distanceMeters: Double, deltaSeconds: Double): Double {
        if (deltaSeconds <= 0.0) return 0.0
        return distanceMeters / deltaSeconds
    }

    fun msToKmh(metersPerSecond: Double): Double = metersPerSecond * 3.6

    fun kmhToMs(kmh: Double): Double = kmh / 3.6

    /** 確保角度落在 0..360 之間。 */
    fun normalizeDegrees(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    const val DEG_TO_RAD = PI / 180.0
}
