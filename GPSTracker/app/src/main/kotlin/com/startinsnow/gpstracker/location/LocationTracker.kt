package com.startinsnow.gpstracker.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.startinsnow.gpstracker.core.model.FilteredLocation
import com.startinsnow.gpstracker.core.model.LocationProvider
import com.startinsnow.gpstracker.core.model.LocationSample
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * GPS Engine 核心模組，對應規格「73. GPS Engine / LocationTracker」。
 *
 * 刻意只使用 android.location.LocationManager（GPS_PROVIDER / NETWORK_PROVIDER），
 * 不依賴 Google Play Services Fused Location，確保：
 *  - 沒有 Google Play Services 的裝置也能記錄。
 *  - 完全不需要網路即可取得 GNSS 定位（Offline First，規格 6）。
 *  - 與規格「3. 絕對禁止使用的地圖技術」保持核心 GPS 邏輯與任何 Google 服務解耦。
 *
 * 不直接把邏輯寫在 Activity/Service 裡：本類別只負責 Location subscription + 轉換 +
 * 交給 [GpsDriftFilter] 判斷可信度，是否要進一步用於 Distance/Speed/Movement 由呼叫端決定。
 */
class LocationTracker(private val context: Context) {

    private val locationManager: LocationManager
        get() = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    val driftFilter = GpsDriftFilter()
    val qualityManager = LocationQualityManager()

    @SuppressLint("MissingPermission")
    fun observe(
        trackId: String,
        gpsMinTimeMs: Long = GPS_MIN_TIME_MS,
        gpsMinDistanceM: Float = GPS_MIN_DISTANCE_M,
        networkMinTimeMs: Long = NETWORK_MIN_TIME_MS,
        networkMinDistanceM: Float = NETWORK_MIN_DISTANCE_M
    ): Flow<FilteredLocation> = callbackFlow {
        driftFilter.reset()
        qualityManager.reset()

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val sample = location.toLocationSample(trackId)
                qualityManager.onLocationReceived(sample.elapsedRealtimeMs)
                val filtered = driftFilter.evaluate(sample)
                trySend(filtered)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }

        val providers = mutableListOf<String>()
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    gpsMinTimeMs,
                    gpsMinDistanceM,
                    listener,
                    context.mainLooper
                )
                providers += LocationManager.GPS_PROVIDER
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    networkMinTimeMs,
                    networkMinDistanceM,
                    listener,
                    context.mainLooper
                )
                providers += LocationManager.NETWORK_PROVIDER
            }
        } catch (e: SecurityException) {
            close(e)
        }

        awaitClose {
            locationManager.removeUpdates(listener)
        }
    }

    /** 取得目前已知最佳位置（供「開始記錄」立即顯示用），不會啟動持續訂閱。 */
    @SuppressLint("MissingPermission")
    fun getLastKnownBestLocation(): LocationSample? {
        val candidates = listOfNotNull(
            runCatching { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull(),
            runCatching { locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                runCatching { locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) }.getOrNull()
            } else null
        )
        return candidates.minByOrNull { it.accuracy }?.toLocationSample(trackId = "")
    }

    fun isAnyProviderEnabled(): Boolean = runCatching {
        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }.getOrDefault(false)

    companion object {
        const val GPS_MIN_TIME_MS = 2_000L
        const val GPS_MIN_DISTANCE_M = 0f
        const val NETWORK_MIN_TIME_MS = 5_000L
        const val NETWORK_MIN_DISTANCE_M = 0f

        fun Location.toLocationSample(trackId: String): LocationSample = LocationSample(
            latitude = latitude,
            longitude = longitude,
            timestampMs = time,
            elapsedRealtimeMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                elapsedRealtimeNanos / 1_000_000L
            } else {
                SystemClock.elapsedRealtime()
            },
            speedMps = if (hasSpeed()) speed else null,
            speedAccuracyMps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasSpeedAccuracy()) {
                speedAccuracyMetersPerSecond
            } else null,
            altitudeMeters = if (hasAltitude()) altitude else null,
            altitudeAccuracyMeters = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasVerticalAccuracy()) {
                verticalAccuracyMeters
            } else null,
            bearingDegrees = if (hasBearing()) bearing else null,
            bearingAccuracyDegrees = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasBearingAccuracy()) {
                bearingAccuracyDegrees
            } else null,
            accuracyMeters = if (hasAccuracy()) accuracy else 999f,
            provider = when (provider) {
                LocationManager.GPS_PROVIDER -> LocationProvider.GPS
                LocationManager.NETWORK_PROVIDER -> LocationProvider.NETWORK
                LocationManager.PASSIVE_PROVIDER -> LocationProvider.PASSIVE
                else -> LocationProvider.UNKNOWN
            },
            trackId = trackId
        )
    }
}
