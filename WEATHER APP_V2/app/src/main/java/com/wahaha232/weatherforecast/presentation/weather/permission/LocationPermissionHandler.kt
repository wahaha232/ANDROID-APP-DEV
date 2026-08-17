// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/presentation/weather/permission/LocationPermissionHandler.kt
package com.wahaha232.weatherforecast.presentation.weather.permission

import android.Manifest
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.shouldShowRationale

/**
 * 使用 Accompanist Permissions 請求定位權限（FINE + COARSE）。
 * onPermissionResult 用 rememberUpdatedState 包裝，避免因 Composable 重組
 * 而持有過期的舊 lambda 造成的間接記憶體洩漏／行為過期問題。
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun rememberLocationPermissionState(
    onPermissionResult: (granted: Boolean) -> Unit
): LocationPermissionController {
    val currentOnResult = rememberUpdatedState(onPermissionResult)

    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    ) { results ->
        val granted = results.values.any { it }
        currentOnResult.value(granted)
    }

    return LocationPermissionController(permissionsState)
}

@OptIn(ExperimentalPermissionsApi::class)
class LocationPermissionController internal constructor(
    private val multiplePermissionsState: com.google.accompanist.permissions.MultiplePermissionsState
) {
    val isGranted: Boolean get() = multiplePermissionsState.permissions.any { it.status.isGranted }
    val shouldShowRationale: Boolean get() = multiplePermissionsState.permissions.any { it.status.shouldShowRationale }

    fun requestPermission() {
        multiplePermissionsState.launchMultiplePermissionRequest()
    }
}

/**
 * 定位權限請求提示卡片：說明用途 + 允許/略過按鈕，符合 Google Play 對定位權限使用情境的透明度要求。
 */
@Composable
fun LocationPermissionRationaleCard(
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "允許「天氣預報」存取此裝置的位置資訊？",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "為了提供最準確的當地即時天氣，我們需要存取您的裝置位置。",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAllow, modifier = Modifier.fillMaxWidth()) {
            Text("使用應用程式時允許")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text("拒絕（手動搜尋城市）")
        }
    }
}

