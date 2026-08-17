// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/data/repository/LocationRepositoryImpl.kt
package com.wahaha232.weatherforecast.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.wahaha232.weatherforecast.domain.model.Coordinates
import com.wahaha232.weatherforecast.domain.repository.LocationRepository
import com.wahaha232.weatherforecast.domain.util.Resource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class LocationRepositoryImpl(
    private val context: Context,
    private val fusedLocationClient: FusedLocationProviderClient
) : LocationRepository {

    private fun hasLocationPermission(): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fineGranted || coarseGranted
    }

    override suspend fun getCurrentCoordinates(): Resource<Coordinates> {
        if (!hasLocationPermission()) {
            return Resource.Error("尚未取得定位權限，請於系統設定中允許定位存取")
        }

        return try {
            val coordinates = requestCurrentLocation() ?: requestLastKnownLocation()
            if (coordinates != null) {
                Resource.Success(coordinates)
            } else {
                Resource.Error("無法取得目前 GPS 座標，請確認裝置定位服務已開啟")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            Resource.Error("定位權限不足，請於系統設定中允許定位存取", e)
        } catch (e: Exception) {
            Resource.Error(e.message ?: "定位時發生未知錯誤", e)
        }
    }

    @Suppress("MissingPermission")
    private suspend fun requestCurrentLocation(): Coordinates? = suspendCancellableCoroutine { continuation ->
        val cancellationTokenSource = CancellationTokenSource()
        continuation.invokeOnCancellation { cancellationTokenSource.cancel() }

        fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            cancellationTokenSource.token
        ).addOnSuccessListener { location ->
            val result = location?.let { Coordinates(it.latitude, it.longitude) }
            if (continuation.isActive) continuation.resume(result)
        }.addOnFailureListener {
            if (continuation.isActive) continuation.resume(null)
        }
    }

    @Suppress("MissingPermission")
    private suspend fun requestLastKnownLocation(): Coordinates? = suspendCancellableCoroutine { continuation ->
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            val result = location?.let { Coordinates(it.latitude, it.longitude) }
            if (continuation.isActive) continuation.resume(result)
        }.addOnFailureListener {
            if (continuation.isActive) continuation.resume(null)
        }
    }
}
