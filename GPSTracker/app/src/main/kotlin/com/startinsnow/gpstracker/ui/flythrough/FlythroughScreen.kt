package com.startinsnow.gpstracker.ui.flythrough

import android.app.Activity
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.util.Base64
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.startinsnow.gpstracker.GpsTrackerApplication
import com.startinsnow.gpstracker.export.TerrainProviderConfig
import com.startinsnow.gpstracker.export.TileProviderConfig
import com.startinsnow.gpstracker.service.FlyoverRecorderState
import com.startinsnow.gpstracker.service.FlyoverRecordingService
import com.startinsnow.gpstracker.ui.detail.TrackDetailViewModel
import com.startinsnow.gpstracker.ui.share.ShareUtils
import org.json.JSONArray
import org.json.JSONObject

private val SPEED_OPTIONS = listOf(1.0, 2.0, 4.0, 8.0, 16.0)

// 一鍵分享快速路徑的目標 App（實際安裝與否會再檢查，未安裝則退回系統分享面板）。
private val INSTAGRAM_PACKAGES = listOf("com.instagram.android")
private val TIKTOK_PACKAGES = listOf(
    "com.zhiliaoapp.musically",
    "com.ss.android.ugc.trill",
    "com.zhiliaoapp.musically.go"
)

/**
 * 3D 飛越：WebView 內用 MapLibre GL JS（開源 BSD-3）的 globe 投影，
 * 圖磚與地形使用 0 元、免金鑰的來源（Esri 衛星 + AWS Terrarium DEM）。
 * 相機沿 GPS 軌跡飛行，可錄成 MP4 並透過系統分享面板分享到社群。
 */
@SuppressLint("JavascriptInterface", "SetJavaScriptEnabled")
@Composable
fun FlythroughScreen(trackId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as GpsTrackerApplication
    val viewModel: TrackDetailViewModel = viewModel(
        factory = TrackDetailViewModel.factory(app, trackId)
    )

    val points by viewModel.points.collectAsState()
    val track by viewModel.track.collectAsState()
    val isRecording by FlyoverRecorderState.isRecording.collectAsState()
    val lastFile by FlyoverRecorderState.lastFile.collectAsState()

    var webView by remember { mutableStateOf<WebView?>(null) }
    var mapReady by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(4.0) }

    val bridge: FlythroughBridge = remember {
        FlythroughBridge(onReady = { mapReady = true })
    }
    val progress by bridge.progress.collectAsState()
    val snapshotUrl by bridge.snapshotDataUrl.collectAsState()
    val finishedCount by bridge.finishedCount.collectAsState()
    val hudValues by bridge.hud.collectAsState()
    var showShareMenu by remember { mutableStateOf(false) }

    val baseConfig = TileProviderConfig.FREE_ESRI_SATELLITE
    val terrainConfig = TerrainProviderConfig.AWS_TERRARIUM

    fun js(code: String) {
        webView?.evaluateJavascript(code, null)
    }

    // 地圖就緒後：帶入免費圖資來源。
    LaunchedEffect(mapReady) {
        if (mapReady) {
            js(
                "initSources(${JSONObject.quote(baseConfig.tileUrlTemplate)}, " +
                    "${JSONObject.quote(terrainConfig.demUrlTemplate)}, " +
                    "${JSONObject.quote(terrainConfig.encoding)})"
            )
        }
    }

    // 把 GPS 軌跡（GeoJSON）＋每點的 HUD 資料（速度/高度）餵進 WebView。
    LaunchedEffect(points, mapReady) {
        if (!mapReady) return@LaunchedEffect
        val trusted = points.filter { it.reliability == "TRUSTED" }.sortedBy { it.timestampMs }
        if (trusted.size < 2) return@LaunchedEffect
        val coordsArray = JSONArray()
        val hudArray = JSONArray()
        trusted.forEach { point ->
            coordsArray.put(JSONArray().put(point.longitude).put(point.latitude))
            hudArray.put(
                JSONArray()
                    .put((point.speedMps?.toDouble() ?: 0.0) * 3.6)
                    .put(point.altitudeMeters ?: 0.0)
            )
        }
        val geometry = JSONObject().put("type", "LineString").put("coordinates", coordsArray)
        val feature = JSONObject()
            .put("type", "Feature")
            .put("properties", JSONObject())
            .put("geometry", geometry)
        js("setRoute(${JSONObject.quote(feature.toString())}, ${JSONObject.quote(hudArray.toString())})")
    }

    // 分享快照。
    LaunchedEffect(snapshotUrl) {
        val url = snapshotUrl ?: return@LaunchedEffect
        bridge.snapshotDataUrl.value = null
        val base64 = url.substringAfter("base64,", "")
        if (base64.isNotEmpty()) {
            runCatching {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()?.let { bitmap ->
                val annotated = annotateHud(bitmap, hudValues)
                ShareUtils.shareBitmap(context, annotated, "flyover_${System.currentTimeMillis()}.png", "分享 3D 飛越快照")
            }
        }
    }

    // 播放結束時自動停止錄影。
    LaunchedEffect(finishedCount) {
        if (finishedCount > 0 && isRecording) {
            FlyoverRecordingService.stop(context)
        }
    }

    val projectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            FlyoverRecordingService.start(context, result.resultCode, data)
            js("seek(0); startFly();")
            isPlaying = true
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("3D 飛越", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(0xFF000000.toInt())
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.setSupportZoom(false)
                        settings.builtInZoomControls = false
                        settings.mediaPlaybackRequiresUserGesture = false
                        addJavascriptInterface(bridge, "AndroidBridge")
                        loadUrl("file:///android_asset/flythrough.html")
                        webView = this
                    }
                }
            )

            // 播放中顯示影片進度。
            if (isPlaying) {
                track?.let { t ->
                    Card(
                        modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                        )
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(
                                "${t.dominantMode.emoji} ${"%.2f".format(t.distanceMeters / 1000.0)} km",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "MapLibre GL JS globe · 免費圖資",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (isRecording) {
                Row(
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.FiberManualRecord,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("REC", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            }

            if (!isRecording) {
                FlythroughControls(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    isPlaying = isPlaying,
                    speed = speed,
                    progress = progress,
                    onPlayPause = {
                        if (isPlaying) {
                            js("pauseFly()")
                            isPlaying = false
                        } else {
                            js("startFly()")
                            isPlaying = true
                        }
                    },
                    onRestart = {
                        js("seek(0); startFly();")
                        isPlaying = true
                    },
                    onSeek = { fraction ->
                        isPlaying = false
                        js("pauseFly(); seek($fraction);")
                    },
                    onSpeed = { speed = it; js("setSpeed($it)") },
                    onSnapshot = { js("snapshot()") },
                    onRecord = { captureLauncher.launch(projectionManager.createScreenCaptureIntent()) },
                    sharedFile = lastFile,
                    onShareVideo = { showShareMenu = true }
                )
            } else {
                Button(
                    onClick = {
                        js("pauseFly()")
                        isPlaying = false
                        FlyoverRecordingService.stop(context)
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("停止錄影")
                }
            }
        }
    }

    if (showShareMenu && lastFile != null) {
        val videoFile = lastFile!!
        AlertDialog(
            onDismissRequest = { showShareMenu = false },
            title = { Text("分享 3D 飛越影片") },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            showShareMenu = false
                            ShareUtils.shareToApps(context, videoFile, "video/mp4", INSTAGRAM_PACKAGES, "分享到 Instagram")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("分享到 Instagram") }
                    TextButton(
                        onClick = {
                            showShareMenu = false
                            ShareUtils.shareToApps(context, videoFile, "video/mp4", TIKTOK_PACKAGES, "分享到 TikTok")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("分享到 TikTok") }
                    TextButton(
                        onClick = {
                            showShareMenu = false
                            ShareUtils.shareFile(context, videoFile, "video/mp4", "分享 3D 飛越影片")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("其他應用程式（系統分享面板）") }
                }
            },
            confirmButton = {
                TextButton(onClick = { showShareMenu = false }) { Text("取消") }
            }
        )
    }

    // 離開畫面時釋放 WebView，避免資源洩漏。
    DisposableEffect(Unit) {
        onDispose {
            webView?.let {
                it.removeJavascriptInterface("AndroidBridge")
                it.stopLoading()
                it.destroy()
            }
            webView = null
        }
    }
}

@Composable
private fun FlythroughControls(
    modifier: Modifier,
    isPlaying: Boolean,
    speed: Double,
    progress: Float,
    onPlayPause: () -> Unit,
    onRestart: () -> Unit,
    onSeek: (Float) -> Unit,
    onSpeed: (Double) -> Unit,
    onSnapshot: () -> Unit,
    onRecord: () -> Unit,
    sharedFile: java.io.File?,
    onShareVideo: () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Slider(value = progress, onValueChange = onSeek)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onPlayPause) {
                    Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (isPlaying) "暫停" else "播放")
                }
                IconButton(onClick = onRestart) {
                    Icon(Icons.Filled.Refresh, contentDescription = "重播")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SPEED_OPTIONS.forEach { option ->
                        FilterChip(
                            selected = speed == option,
                            onClick = { onSpeed(option) },
                            label = { Text("${option.toInt()}×") }
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onSnapshot, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("快照")
                }
                Button(onClick = onRecord, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Videocam, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("錄影")
                }
                if (sharedFile != null) {
                    OutlinedButton(onClick = onShareVideo, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("分享")
                    }
                }
            }
        }
    }
}

/** 把 HUD（距離／速度／高度）疊加到地圖快照上，讓分享出去的圖片也有資訊。 */
private fun annotateHud(source: Bitmap, hud: FlyoverHud): Bitmap {
    val result = source.copy(Bitmap.Config.ARGB_8888, true) ?: return source
    val scale = (source.width / 1080f).coerceAtLeast(0.5f)
    val canvas = Canvas(result)
    val pad = 18f * scale
    val paintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC0A0F19.toInt() }
    val rect = RectF(pad, pad, pad + 250f * scale, pad + 118f * scale)
    canvas.drawRoundRect(rect, 22f * scale, 22f * scale, paintBg)
    val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        typeface = Typeface.DEFAULT_BOLD
    }
    paintText.textSize = 42f * scale
    canvas.drawText("%.2f km".format(hud.distanceKm), pad + 20f * scale, pad + 50f * scale, paintText)
    paintText.typeface = Typeface.DEFAULT
    paintText.textSize = 26f * scale
    canvas.drawText("速度 %.0f km/h".format(hud.speedKmh), pad + 20f * scale, pad + 84f * scale, paintText)
    canvas.drawText("高度 %.0f m".format(hud.altitudeM), pad + 20f * scale, pad + 114f * scale, paintText)
    return result
}
