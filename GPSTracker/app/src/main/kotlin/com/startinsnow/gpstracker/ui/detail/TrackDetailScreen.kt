package com.startinsnow.gpstracker.ui.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.startinsnow.gpstracker.GpsTrackerApplication
import com.startinsnow.gpstracker.core.model.UploadStatus
import com.startinsnow.gpstracker.export.TrackZipExporter
import com.startinsnow.gpstracker.sync.DriveSyncManager
import com.startinsnow.gpstracker.sync.GoogleAuthManager
import com.startinsnow.gpstracker.ui.map.GpsMapController
import com.startinsnow.gpstracker.ui.map.GpsMapView
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private fun isOnWifi(context: android.content.Context): Boolean {
    val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val capabilities = cm.getNetworkCapabilities(network) ?: return false
    return capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
}

@Composable
fun TrackDetailScreen(trackId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as GpsTrackerApplication
    val viewModel: TrackDetailViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = TrackDetailViewModel.factory(app, trackId)
    )
    val scope = rememberCoroutineScope()

    val track by viewModel.track.collectAsState()
    val points by viewModel.points.collectAsState()
    val photos by viewModel.photos.collectAsState()
    val playback by viewModel.playback.collectAsState()

    var mapController by remember { mutableStateOf<GpsMapController?>(null) }
    var uploadStatusText by remember { mutableStateOf<String?>(null) }

    val authManager = remember { GoogleAuthManager(context) }
    val driveSyncManager = remember { DriveSyncManager() }

    val signInLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        scope.launch {
            val account = runCatching { task.result }.getOrNull()
            if (account == null) {
                uploadStatusText = "登入失敗"
                return@launch
            }
            val currentSettings = app.settingsRepository.settings.first()
            if (currentSettings.wifiOnlySync && !isOnWifi(context)) {
                uploadStatusText = "設定為僅限 Wi-Fi 上傳，目前非 Wi-Fi 連線"
                return@launch
            }
            uploadStatusText = "上傳中…"
            val data = viewModel.buildExportData()
            if (data == null) {
                uploadStatusText = "沒有可上傳的資料"
                return@launch
            }
            val exportDir = TrackZipExporter.buildExportDirectory(app.filesDir, data)
            val drivePath = TrackZipExporter.relativeDrivePath(data.track.startTimeMs)
            app.trackRepository.setUploadStatus(trackId, UploadStatus.UPLOADING)
            val tokenResult = authManager.getAccessToken(account)
            val token = tokenResult.getOrNull()
            if (token == null) {
                uploadStatusText = "無法取得 Google 授權（可能尚未設定 OAuth Client）"
                app.trackRepository.setUploadStatus(trackId, UploadStatus.FAILED)
                return@launch
            }
            val uploadResult = driveSyncManager.uploadTrackPackage(token, exportDir, drivePath)
            if (uploadResult.isSuccess) {
                uploadStatusText = "已上傳到 Google Drive"
                app.trackRepository.setUploadStatus(trackId, UploadStatus.UPLOADED)
                if (currentSettings.deleteLocalAfterUploadEnabled) {
                    viewModel.deleteTrack()
                    onBack()
                }
            } else {
                uploadStatusText = "上傳失敗：${uploadResult.exceptionOrNull()?.message}"
                app.trackRepository.setUploadStatus(trackId, UploadStatus.FAILED)
            }
        }
    }

    LaunchedEffect(points, mapController) {
        val controller = mapController ?: return@LaunchedEffect
        val trusted = points.filter { it.reliability == "TRUSTED" }.sortedBy { it.timestampMs }
        val coords = trusted.map { it.latitude to it.longitude }
        controller.setRoute(coords)
        coords.firstOrNull()?.let { controller.setPoints(GpsMapController.SOURCE_START, listOf(it)) }
        coords.lastOrNull()?.let { controller.setPoints(GpsMapController.SOURCE_END, listOf(it)) }
        controller.setPoints(
            GpsMapController.SOURCE_PHOTO,
            photos.mapNotNull { p -> if (p.latitude != null && p.longitude != null) p.latitude!! to p.longitude!! else null }
        )
        controller.fitBounds(coords)
    }

    LaunchedEffect(playback.frame, mapController) {
        val frame = playback.frame ?: return@LaunchedEffect
        mapController?.setPlaybackMarker(frame.latitude, frame.longitude)
    }

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            GpsMapView(modifier = Modifier.fillMaxWidth().height(280.dp)) { controller -> mapController = controller }

            Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                track?.let { t ->
                    Text("${t.dominantMode.emoji} ${"%.2f".format(t.distanceMeters / 1000.0)} km", style = MaterialTheme.typography.titleLarge)
                    Text("平均 ${"%.1f".format(t.avgSpeedMps * 3.6)} km/h ・ 最高 ${"%.1f".format(t.maxSpeedMps * 3.6)} km/h")
                    Text("${t.stepCount} steps ・ ${t.photoCount} photos ・ ${t.gpsPointCount} GPS points")
                    Text("上傳狀態：${t.uploadStatus.name}")
                }

                Text("播放：${formatDuration(playback.elapsedMs)} / ${formatDuration(playback.totalDurationMs)}")
                Slider(
                    value = if (playback.totalDurationMs > 0) playback.elapsedMs.toFloat() / playback.totalDurationMs else 0f,
                    onValueChange = { fraction -> viewModel.seekTo((fraction * playback.totalDurationMs).toLong()) }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.play() }) { Text("▶") }
                    Button(onClick = { viewModel.pause() }) { Text("⏸") }
                    Button(onClick = { viewModel.stop() }) { Text("⏹") }
                    listOf(1.0, 2.0, 5.0, 10.0, 20.0).forEach { speed ->
                        OutlinedButton(onClick = { viewModel.setSpeedMultiplier(speed) }) { Text("${speed.toInt()}×") }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            val zip = viewModel.buildExportZip()
                            if (zip != null) {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "application/zip"
                                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(android.content.Intent.createChooser(intent, "分享 GPS TRACKER 匯出檔"))
                            }
                        }
                    }) { Text("匯出 / 分享") }

                    OutlinedButton(onClick = {
                        scope.launch {
                            val currentSettings = app.settingsRepository.settings.first()
                            if (!currentSettings.driveSyncEnabled) {
                                uploadStatusText = "請先在「設定」開啟 Google Drive 同步"
                                return@launch
                            }
                            signInLauncher.launch(authManager.signInIntent())
                        }
                    }) { Text("上傳 Google Drive") }
                }
                uploadStatusText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

                OutlinedButton(onClick = {
                    viewModel.deleteTrack()
                    onBack()
                }) { Text("刪除這筆紀錄") }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}
