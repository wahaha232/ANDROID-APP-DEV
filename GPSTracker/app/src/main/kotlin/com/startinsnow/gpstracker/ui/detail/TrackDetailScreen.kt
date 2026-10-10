package com.startinsnow.gpstracker.ui.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.startinsnow.gpstracker.GpsTrackerApplication
import com.startinsnow.gpstracker.core.geo.RouteSimplifier
import com.startinsnow.gpstracker.core.model.UploadStatus
import com.startinsnow.gpstracker.export.TrackZipExporter
import com.startinsnow.gpstracker.sync.DriveSyncManager
import com.startinsnow.gpstracker.sync.GoogleAuthManager
import com.startinsnow.gpstracker.ui.components.InfoBanner
import com.startinsnow.gpstracker.ui.components.MetricCard
import com.startinsnow.gpstracker.ui.components.formatDuration
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

private data class StatItem(
    val icon: ImageVector,
    val value: String,
    val label: String,
    val unit: String? = null
)

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
    val outages by viewModel.outages.collectAsState()

    var mapController by remember { mutableStateOf<GpsMapController?>(null) }
    var uploadStatusText by remember { mutableStateOf<String?>(null) }

    val authManager = remember { GoogleAuthManager(context) }
    val driveSyncManager = remember { DriveSyncManager() }

    val signInLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (data == null) {
            uploadStatusText = "登入已取消"
            return@rememberLauncherForActivityResult
        }
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
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

    LaunchedEffect(points, photos, outages, mapController) {
        val controller = mapController ?: return@LaunchedEffect
        val trusted = points.filter { it.reliability == "TRUSTED" }.sortedBy { it.timestampMs }
        val coords = trusted.map { it.latitude to it.longitude }
        // 上萬點時縮減「地圖顯示」用的座標，避免字串組裝與 native 繪製造成卡頓 / OOM。
        // 資料庫、GPX / JSON / CSV 匯出仍使用完整資料，不做任何縮減。
        controller.setRoute(RouteSimplifier.simplify(coords))
        coords.firstOrNull()?.let { controller.setPoints(GpsMapController.SOURCE_START, listOf(it)) }
        coords.lastOrNull()?.let { controller.setPoints(GpsMapController.SOURCE_END, listOf(it)) }
        controller.setPoints(
            GpsMapController.SOURCE_PHOTO,
            photos.mapNotNull { p -> if (p.latitude != null && p.longitude != null) p.latitude!! to p.longitude!! else null }
        )
        // GPS 中斷標記：標在「中斷開始前的最後一個可信點」位置（真實資料，不造假點）。
        controller.setPoints(
            GpsMapController.SOURCE_OUTAGE,
            outages.mapNotNull { outage ->
                trusted.lastOrNull { it.timestampMs <= outage.startTimestampMs } ?: trusted.firstOrNull()
            }.map { it.latitude to it.longitude }
        )
        controller.fitBounds(coords)
    }

    LaunchedEffect(playback.frame, mapController) {
        val frame = playback.frame ?: return@LaunchedEffect
        mapController?.setPlaybackMarker(frame.latitude, frame.longitude)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(track?.let { "${it.dominantMode.emoji} 軌跡詳情" } ?: "軌跡詳情", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .clip(MaterialTheme.shapes.large)
            ) {
                GpsMapView(modifier = Modifier.fillMaxSize()) { controller -> mapController = controller }
            }

            track?.let { t ->
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "%.2f".format(t.distanceMeters / 1000.0),
                        style = MaterialTheme.typography.displaySmall
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "km",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }

                val rows = remember(t) {
                    listOf(
                        StatItem(Icons.Filled.Timer, formatDuration(t.durationMs), "持續時間"),
                        StatItem(Icons.Filled.Speed, "%.1f".format(t.avgSpeedMps * 3.6), "平均速度", "km/h"),
                        StatItem(Icons.Filled.Speed, "%.1f".format(t.maxSpeedMps * 3.6), "最高速度", "km/h"),
                        StatItem(Icons.Filled.DirectionsWalk, t.stepCount.toString(), "步數"),
                        StatItem(Icons.Filled.PhotoCamera, t.photoCount.toString(), "照片"),
                        StatItem(Icons.Filled.MyLocation, t.gpsPointCount.toString(), "定位點"),
                        StatItem(Icons.Filled.TrendingUp, "%.0f".format(t.totalAscentMeters), "總爬升", "m"),
                        StatItem(Icons.Filled.TrendingDown, "%.0f".format(t.totalDescentMeters), "總下降", "m")
                    ).chunked(2)
                }
                rows.forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rowItems.forEach { item ->
                            MetricCard(
                                icon = item.icon,
                                value = item.value,
                                label = item.label,
                                unit = item.unit,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            PlaybackCard(
                isPlaying = playback.isPlaying,
                elapsedMs = playback.elapsedMs,
                totalDurationMs = playback.totalDurationMs,
                speedMultiplier = playback.speedMultiplier,
                onSeek = { fraction -> viewModel.seekTo((fraction * playback.totalDurationMs).toLong()) },
                onPlayPause = { if (playback.isPlaying) viewModel.pause() else viewModel.play() },
                onStop = { viewModel.stop() },
                onSpeed = { viewModel.setSpeedMultiplier(it) }
            )

            uploadStatusText?.let { text ->
                InfoBanner(text = text, color = MaterialTheme.colorScheme.primary)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
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
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("匯出")
                }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val currentSettings = app.settingsRepository.settings.first()
                            if (!currentSettings.driveSyncEnabled) {
                                uploadStatusText = "請先在「設定」開啟 Google Drive 同步"
                                return@launch
                            }
                            signInLauncher.launch(authManager.signInIntent())
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("上傳")
                }
            }

            OutlinedButton(
                onClick = {
                    viewModel.deleteTrack()
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("刪除這筆紀錄")
            }
        }
    }
}

@Composable
private fun PlaybackCard(
    isPlaying: Boolean,
    elapsedMs: Long,
    totalDurationMs: Long,
    speedMultiplier: Double,
    onSeek: (Float) -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onSpeed: (Double) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("軌跡回放", style = MaterialTheme.typography.titleMedium)
            Text(
                "${formatDuration(elapsedMs)} / ${formatDuration(totalDurationMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = if (totalDurationMs > 0) elapsedMs.toFloat() / totalDurationMs else 0f,
                onValueChange = onSeek
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(onClick = onPlayPause) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (isPlaying) "暫停" else "播放")
                }
                Button(onClick = onStop) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf(1.0, 2.0, 5.0, 10.0, 20.0)) { speed ->
                    FilterChip(
                        selected = speedMultiplier == speed,
                        onClick = { onSpeed(speed) },
                        label = { Text("${speed.toInt()}×") }
                    )
                }
            }
        }
    }
}
