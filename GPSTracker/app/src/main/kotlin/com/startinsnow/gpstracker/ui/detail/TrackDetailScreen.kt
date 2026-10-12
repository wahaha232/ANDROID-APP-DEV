package com.startinsnow.gpstracker.ui.detail

import com.startinsnow.gpstracker.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.startinsnow.gpstracker.sync.WebDashboardUploader
import com.startinsnow.gpstracker.ui.components.InfoBanner
import com.startinsnow.gpstracker.ui.components.MetricCard
import com.startinsnow.gpstracker.ui.components.formatDuration
import com.startinsnow.gpstracker.ui.map.GpsMapController
import com.startinsnow.gpstracker.ui.map.GpsMapView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
fun TrackDetailScreen(trackId: String, onBack: () -> Unit, onOpenFlythrough: (String) -> Unit = {}) {
    val context = LocalContext.current
    val app = context.applicationContext as GpsTrackerApplication
    val viewModel: TrackDetailViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = TrackDetailViewModel.factory(app, trackId)
    )
    val scope = rememberCoroutineScope()

    val track by viewModel.track.collectAsStateWithLifecycle()
    val points by viewModel.points.collectAsStateWithLifecycle()
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val outages by viewModel.outages.collectAsStateWithLifecycle()

    var mapController by remember { mutableStateOf<GpsMapController?>(null) }
    var uploadStatusText by remember { mutableStateOf<String?>(null) }
    var stepSyncText by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val authManager = remember { GoogleAuthManager(context) }
    val driveSyncManager = remember { DriveSyncManager() }

    val signInLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (data == null) {
            uploadStatusText = context.getString(R.string.detail_001)
            return@rememberLauncherForActivityResult
        }
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        scope.launch {
            val account = runCatching { task.result }.getOrNull()
            if (account == null) {
                uploadStatusText = context.getString(R.string.detail_002)
                return@launch
            }
            val currentSettings = app.settingsRepository.settings.first()
            if (currentSettings.wifiOnlySync && !isOnWifi(context)) {
                uploadStatusText = context.getString(R.string.detail_003)
                return@launch
            }
            uploadStatusText = context.getString(R.string.detail_004)
            val data = viewModel.buildExportData()
            if (data == null) {
                uploadStatusText = context.getString(R.string.detail_005)
                return@launch
            }
            val exportDir = withContext(Dispatchers.IO) { TrackZipExporter.buildExportDirectory(app.filesDir, data) }
            val drivePath = TrackZipExporter.relativeDrivePath(data.track.startTimeMs)
            app.trackRepository.setUploadStatus(trackId, UploadStatus.UPLOADING)
            val tokenResult = authManager.getAccessToken(account)
            val token = tokenResult.getOrNull()
            if (token == null) {
                uploadStatusText = context.getString(R.string.detail_006)
                app.trackRepository.setUploadStatus(trackId, UploadStatus.FAILED)
                return@launch
            }
            // 離開畫面使協程被取消時，狀態不能永遠停在 UPLOADING。
            var uploadResult = try {
                driveSyncManager.uploadTrackPackage(token, exportDir, drivePath)
            } catch (c: kotlinx.coroutines.CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable) {
                    app.trackRepository.setUploadStatus(trackId, UploadStatus.FAILED)
                }
                throw c
            }
            // 401：access token 過期（或被撤銷）。清掉快取的 token、重取一次再試，而不是用同一個壞 token 重試。
            val firstError = uploadResult.exceptionOrNull()
            if (firstError is DriveSyncManager.DriveApiException && firstError.code == 401) {
                authManager.clearAccessToken(account)
                val freshToken = authManager.getAccessToken(account).getOrNull()
                if (freshToken != null) {
                    uploadResult = driveSyncManager.uploadTrackPackage(freshToken, exportDir, drivePath)
                }
            }
            if (uploadResult.isSuccess) {
                uploadStatusText = context.getString(R.string.detail_007)
                app.trackRepository.setUploadStatus(trackId, UploadStatus.UPLOADED)
                if (currentSettings.deleteLocalAfterUploadEnabled) {
                    viewModel.deleteTrack()
                    onBack()
                }
            } else {
                uploadStatusText = context.getString(R.string.detail_008, uploadResult.exceptionOrNull()?.message)
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
                title = { Text(track?.let { stringResource(R.string.detail_009, it.dominantMode.emoji) } ?: stringResource(R.string.detail_010), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.detail_011))
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

            FilledTonalButton(
                onClick = { onOpenFlythrough(trackId) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.detail_012))
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
                        StatItem(Icons.Filled.Timer, formatDuration(t.durationMs), context.getString(R.string.detail_013)),
                        StatItem(Icons.Filled.Speed, "%.1f".format(t.avgSpeedMps * 3.6), context.getString(R.string.detail_014), "km/h"),
                        StatItem(Icons.Filled.Speed, "%.1f".format(t.maxSpeedMps * 3.6), context.getString(R.string.detail_015), "km/h"),
                        StatItem(Icons.Filled.DirectionsWalk, t.stepCount.toString(), context.getString(R.string.settings_019)),
                        StatItem(Icons.Filled.PhotoCamera, t.photoCount.toString(), context.getString(R.string.detail_016)),
                        StatItem(Icons.Filled.MyLocation, t.gpsPointCount.toString(), context.getString(R.string.detail_017)),
                        StatItem(Icons.Filled.TrendingUp, "%.0f".format(t.totalAscentMeters), context.getString(R.string.detail_018), "m"),
                        StatItem(Icons.Filled.TrendingDown, "%.0f".format(t.totalDescentMeters), context.getString(R.string.detail_019), "m")
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
                Text(
                    stringResource(
                        R.string.detail_020,
                        stringResource(com.startinsnow.gpstracker.sync.StepSourceTag.labelRes(t.stepSource))
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (t.status == com.startinsnow.gpstracker.core.model.TrackStatus.FINISHED) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                stepSyncText = context.getString(R.string.detail_021)
                                stepSyncText = viewModel.resyncSteps()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.detail_022)) }
                }
                stepSyncText?.let { text ->
                    InfoBanner(text = text, color = MaterialTheme.colorScheme.primary)
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
                                context.startActivity(android.content.Intent.createChooser(intent, context.getString(R.string.detail_023)))
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.detail_024))
                }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val currentSettings = app.settingsRepository.settings.first()
                            if (!currentSettings.driveSyncEnabled) {
                                uploadStatusText = context.getString(R.string.detail_025)
                                return@launch
                            }
                            signInLauncher.launch(authManager.signInIntent())
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.detail_026))
                }
            }

            OutlinedButton(
                onClick = {
                    scope.launch {
                        val currentSettings = app.settingsRepository.settings.first()
                        if (currentSettings.githubToken.isBlank()) {
                            uploadStatusText = context.getString(R.string.detail_027)
                            return@launch
                        }
                        val data = viewModel.buildExportData()
                        if (data == null) {
                            uploadStatusText = context.getString(R.string.detail_005)
                            return@launch
                        }
                        uploadStatusText = context.getString(R.string.detail_028)
                        val payload = withContext(Dispatchers.IO) { WebDashboardUploader.buildPayload(data) }
                        val webResult = WebDashboardUploader().uploadTrack(
                            token = currentSettings.githubToken,
                            repo = currentSettings.webRepo,
                            branch = currentSettings.webBranch,
                            dataPath = currentSettings.webDataPath,
                            trackId = trackId,
                            payload = payload
                        )
                        webResult.onSuccess {
                            uploadStatusText = context.getString(R.string.detail_029)
                        }.onFailure {
                            uploadStatusText = context.getString(R.string.detail_030, it.message)
                        }
                        // 記下「上傳過」：之後若在網站被刪除，手機才能偵測並詢問要不要一起移除。
                        if (webResult.isSuccess) app.trackRepository.setWebUploadedAt(trackId, System.currentTimeMillis())
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.detail_031))
            }

            OutlinedButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.detail_032))
            }

            if (showDeleteConfirm) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    title = { Text(stringResource(R.string.detail_033)) },
                    text = { Text(stringResource(R.string.detail_034)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showDeleteConfirm = false
                                viewModel.deleteTrack()
                                onBack()
                            }
                        ) { Text(stringResource(R.string.detail_035), color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.detail_036)) }
                    }
                )
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
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.detail_037), style = MaterialTheme.typography.titleMedium)
            Text(
                "${formatDuration(elapsedMs)} / ${formatDuration(totalDurationMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = if (totalDurationMs > 0) elapsedMs.toFloat() / totalDurationMs else 0f,
                onValueChange = onSeek,
                modifier = Modifier.semantics { contentDescription = context.getString(R.string.detail_038) }
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
                    Text(if (isPlaying) stringResource(R.string.detail_039) else stringResource(R.string.detail_040))
                }
                Button(onClick = onStop) {
                    // 只有圖示的按鈕必須有名稱，否則螢幕閱讀器念不出它的用途。
                    Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.detail_041))
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
