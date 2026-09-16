package com.startinsnow.gpstracker.ui.recording

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.startinsnow.gpstracker.core.model.PhotoType
import com.startinsnow.gpstracker.core.model.TrackStatus
import kotlinx.coroutines.launch

private enum class PhotoPromptStage { NONE, START, END }

@Composable
fun RecordingScreen(
    onFinished: (trackId: String?) -> Unit,
    viewModel: RecordingViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsState()

    var hasLocationPermission by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    var hasCameraPermission by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    var photoPromptStage by remember { mutableStateOf(PhotoPromptStage.NONE) }
    var hasPromptedStart by remember { mutableStateOf(false) }
    var cameraReady by remember { mutableStateOf(false) }

    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 背景定位為加分項，被拒絕仍可在前景持續記錄，不阻擋核心功能。 */ }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasLocationPermission = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (hasLocationPermission) {
            viewModel.startRecording()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (state.status != TrackStatus.RECORDING && state.status != TrackStatus.PAUSED) {
            if (hasLocationPermission) {
                viewModel.startRecording()
            } else {
                val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissions += Manifest.permission.POST_NOTIFICATIONS
                }
                locationPermissionLauncher.launch(permissions.toTypedArray())
            }
        }
        if (!hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(state.trackId, state.status) {
        if (state.trackId != null && !hasPromptedStart && state.status == TrackStatus.RECORDING) {
            hasPromptedStart = true
            photoPromptStage = PhotoPromptStage.START
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (hasCameraPermission) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    factory = { ctx ->
                        PreviewView(ctx).also { previewView ->
                            viewModel.photoManager.bindCamera(lifecycleOwner, previewView) { ready ->
                                cameraReady = ready
                            }
                        }
                    }
                )
            }

            Text("${state.movementMode.emoji} ${state.movementMode.name}", style = MaterialTheme.typography.headlineSmall)
            Text("${"%.1f".format(state.currentSpeedKmh)} km/h")
            Text("${"%.2f".format(state.distanceMeters / 1000.0)} km")
            Text(formatDuration(state.durationMs))
            Text("${state.stepCount} steps")
            Text("${state.gpsQuality.emoji} ${state.accuracyLabel}")
            if (state.isAutoPaused) Text("⏸ 自動暫停中（靜止超過 1 分鐘）", color = MaterialTheme.colorScheme.secondary)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        if (hasCameraPermission && cameraReady) {
                            scope.launch { viewModel.capturePhoto(PhotoType.NORMAL) }
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("📷 拍照") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { if (state.status == TrackStatus.PAUSED) viewModel.resumeRecording() else viewModel.pauseRecording() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (state.status == TrackStatus.PAUSED) "▶ 繼續" else "⏸ 暫停") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { photoPromptStage = PhotoPromptStage.END },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("⏹ 結束") }
            }
        }
    }

    if (photoPromptStage != PhotoPromptStage.NONE) {
        val type = if (photoPromptStage == PhotoPromptStage.START) PhotoType.START else PhotoType.END
        AlertDialog(
            onDismissRequest = { },
            title = { Text(if (type == PhotoType.START) "📷 拍攝出發點照片" else "📷 拍攝終點照片") },
            text = { Text("是否要拍攝這張照片？") },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        viewModel.capturePhoto(type)
                        if (type == PhotoType.END) {
                            val finishedTrackId = viewModel.state.value.trackId
                            viewModel.stopRecording()
                            photoPromptStage = PhotoPromptStage.NONE
                            onFinished(finishedTrackId)
                        } else {
                            photoPromptStage = PhotoPromptStage.NONE
                        }
                    }
                }) { Text("拍照") }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    if (type == PhotoType.END) {
                        val finishedTrackId = viewModel.state.value.trackId
                        viewModel.stopRecording()
                        photoPromptStage = PhotoPromptStage.NONE
                        onFinished(finishedTrackId)
                    } else {
                        photoPromptStage = PhotoPromptStage.NONE
                    }
                }) { Text("跳過") }
            }
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}
