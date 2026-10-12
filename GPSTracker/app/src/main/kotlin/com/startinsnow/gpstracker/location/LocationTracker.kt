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
        // 只重設漂移過濾器。「從未收到定位」的計時基準是由 TrackingForegroundService
        // 在 startRecording() 呼叫 qualityManager.startTracking() 設定的，這裡若再呼叫
        // qualityManager.reset() 會把基準清成 null，導致 GPS Lost / 尚未收到定位偵測永久失效。
        driftFilter.reset()

        val registered = mutableSetOf<String>()
        var lastGpsElapsedMs = Long.MIN_VALUE
        lateinit var listener: LocationListener

        /** 註冊單一 provider；已註冊或目前未啟用則略過（之後被啟用時由 onProviderEnabled 再補註冊）。 */
        fun register(provider: String) {
            if (provider in registered) return
            try {
                if (!locationManager.isProviderEnabled(provider)) return
                if (provider == LocationManager.GPS_PROVIDER) {
                    locationManager.requestLocationUpdates(provider, gpsMinTimeMs, gpsMinDistanceM, listener, context.mainLooper)
                } else {
                    locationManager.requestLocationUpdates(provider, networkMinTimeMs, networkMinDistanceM, listener, context.mainLooper)
                }
                registered += provider
            } catch (e: SecurityException) {
                close(e)
            }
        }

        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val sample = location.toLocationSample(trackId)
                if (sample.provider == LocationProvider.GPS) {
                    lastGpsElapsedMs = sample.elapsedRealtimeMs
                } else if (GpsSourcePolicy.shouldIgnoreNetworkFix(sample.provider, sample.elapsedRealtimeMs, lastGpsElapsedMs)) {
                    // GPS 剛有定位時忽略網路定位（理由見 GpsSourcePolicy）。
                    return
                }
                qualityManager.onLocationReceived(sample.elapsedRealtimeMs)
                val filtered = driftFilter.evaluate(sample)
                trySend(filtered)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            // 開始記錄時定位功能是關的、之後才開啟：補註冊，否則整趟都收不到點。
            override fun onProviderEnabled(provider: String) = register(provider)
            override fun onProviderDisabled(provider: String) = Unit
        }

        register(LocationManager.GPS_PROVIDER)
        register(LocationManager.NETWORK_PROVIDER)

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
        // 未設定 accuracy 的 Location，其 getAccuracy() 回傳 0，會讓 minByOrNull 誤選最不可靠的點；
        // 因此沒有 accuracy 的候選一律排到最後，避免用「看似最準」的無效值當起始點。
        val best = candidates.minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }
            ?: candidates.firstOrNull()
        return best?.toLocationSample(trackId = "")
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
