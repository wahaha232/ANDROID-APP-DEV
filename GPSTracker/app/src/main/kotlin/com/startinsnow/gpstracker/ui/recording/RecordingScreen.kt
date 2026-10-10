package com.startinsnow.gpstracker.ui.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.startinsnow.gpstracker.core.model.PhotoType
import com.startinsnow.gpstracker.core.model.TrackStatus
import com.startinsnow.gpstracker.service.TrackingStateHolder
import com.startinsnow.gpstracker.ui.components.InfoBanner
import com.startinsnow.gpstracker.ui.components.StatusChip
import com.startinsnow.gpstracker.ui.components.formatDuration
import com.startinsnow.gpstracker.ui.theme.gpsQualityColor
import kotlinx.coroutines.launch

private enum class PhotoPromptStage { NONE, START, END }

private fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

@Composable
fun RecordingScreen(
    onFinished: (trackId: String?) -> Unit,
    onClose: () -> Unit = {},
    resumeTrackId: String? = null,
    viewModel: RecordingViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsState()

    var hasFineLocation by remember { mutableStateOf(context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) }
    var hasCoarseLocation by remember { mutableStateOf(context.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) }
    var hasCameraPermission by remember { mutableStateOf(context.hasPermission(Manifest.permission.CAMERA)) }
    var cameraReady by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var photoPromptStage by rememberSaveable { mutableStateOf(PhotoPromptStage.NONE) }
    var hasPromptedStart by rememberSaveable { mutableStateOf(false) }

    val hasLocationPermission = hasFineLocation || hasCoarseLocation

    /** 只有「確實需要」才啟動記錄：權限不足時先要權限，避免同時要權限又啟動造成競態。 */
    fun startOrResumeRecording() {
        val current = viewModel.state.value
        if (current.status == TrackStatus.RECORDING || current.status == TrackStatus.PAUSED || current.isStarting) return
        if (resumeTrackId != null) {
            viewModel.startResumeRecording(resumeTrackId)
        } else {
            viewModel.startRecording()
        }
    }

    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 背景定位為加分項，被拒絕仍可在前景持續記錄，不阻擋核心功能。 */ }

    // 單一權限請求入口：同時 launch 兩個權限請求會讓 Android 的結果回呼互相干擾
    // （會造成相機權限永遠拿不到 → 沒有預覽 → 拍照永遠失敗）。
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasFineLocation = results[Manifest.permission.ACCESS_FINE_LOCATION]
            ?: context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        hasCoarseLocation = results[Manifest.permission.ACCESS_COARSE_LOCATION]
            ?: context.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        hasCameraPermission = results[Manifest.permission.CAMERA]
            ?: context.hasPermission(Manifest.permission.CAMERA)
        if (hasCameraPermission) {
            cameraError = null
        } else {
            cameraError = "沒有相機權限，無法拍照"
        }

        // 必須用「剛更新過的 state 變數」重新判斷，不可用組合期算出的 hasLocationPermission 值，
        // 否則剛授權成功時可能被誤判成沒有權限（該 val 是舊一次組合的值）。
        if (!(hasFineLocation || hasCoarseLocation)) {
            TrackingStateHolder.postMessage("需要定位權限才能記錄軌跡，請允許「精確位置」後再試一次")
        } else {
            startOrResumeRecording()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                !context.hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            ) {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }

    LaunchedEffect(Unit) {
        val missing = buildList {
            if (!hasFineLocation && !hasCoarseLocation) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (!hasCameraPermission) add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            startOrResumeRecording()
        }
    }

    // 只在「本畫面真的新建了一筆 Track」時才提示拍出發點照片；
    // 續錄（Crash Recovery）回來的 Track 不應再被要求拍出發點照片。
    LaunchedEffect(state.trackId, state.status) {
        if (state.trackId != null && !hasPromptedStart &&
            state.status == TrackStatus.RECORDING && viewModel.startedNewTrackFromThisScreen
        ) {
            hasPromptedStart = true
            photoPromptStage = PhotoPromptStage.START
        }
    }

    // 離開畫面時釋放相機資源，避免下次進來時 use case 仍被佔用而無法拍照。
    DisposableEffect(Unit) {
        onDispose { viewModel.photoManager.unbind() }
    }

    // 使用者可能到系統設定改權限後再回到 App：ON_RESUME 時重新檢查權限，
    // 否則畫面會一直停留在「沒有權限」而無法開始記錄。
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasFineLocation = context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                hasCoarseLocation = context.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                hasCameraPermission = context.hasPermission(Manifest.permission.CAMERA)
                if (hasFineLocation || hasCoarseLocation) {
                    startOrResumeRecording()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (state.status) {
                            TrackStatus.PAUSED -> "已暫停"
                            else -> "記錄中"
                        },
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "收起")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FilledTonalButton(
                        onClick = {
                            if (state.status == TrackStatus.PAUSED) viewModel.resumeRecording() else viewModel.pauseRecording()
                        },
                        modifier = Modifier.weight(1f).height(54.dp)
                    ) {
                        Icon(
                            if (state.status == TrackStatus.PAUSED) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.status == TrackStatus.PAUSED) "繼續" else "暫停")
                    }
                    Button(
                        onClick = { photoPromptStage = PhotoPromptStage.END },
                        modifier = Modifier.weight(1f).height(54.dp)
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("結束")
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusChip(
                    text = state.accuracyLabel,
                    color = gpsQualityColor(state.gpsQuality)
                )
                StatusChip(
                    text = "${state.movementMode.emoji} ${state.movementMode.name}",
                    color = MaterialTheme.colorScheme.primary
                )
            }

            TrackMetricsCard(state)

            if (state.isAutoPaused) {
                InfoBanner(
                    text = "自動暫停中（靜止超過 1 分鐘）",
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            if (!hasLocationPermission) {
                InfoBanner(
                    text = "沒有定位權限，尚未開始記錄",
                    color = MaterialTheme.colorScheme.error,
                    icon = Icons.Filled.ErrorOutline
                )
            }

            state.message?.let { message ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoBanner(text = message, color = MaterialTheme.colorScheme.error, icon = Icons.Filled.ErrorOutline)
                    OutlinedButton(
                        onClick = { TrackingStateHolder.clearMessage() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("知道了") }
                }
            }

            if (hasCameraPermission) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(MaterialTheme.shapes.large)
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            PreviewView(ctx).also { previewView ->
                                viewModel.photoManager.bindCamera(lifecycleOwner, previewView) { result ->
                                    cameraReady = result.isSuccess
                                    cameraError = result.exceptionOrNull()?.let {
                                        "相機無法使用：${it.message ?: it::class.java.simpleName}"
                                    }
                                }
                            }
                        }
                    )
                }
            }

            FilledTonalButton(
                onClick = {
                    when {
                        !hasCameraPermission -> permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                        !cameraReady -> TrackingStateHolder.postMessage(cameraError ?: "相機正在準備中，請稍候再試")
                        else -> scope.launch {
                            viewModel.capturePhoto(PhotoType.NORMAL).onFailure { error ->
                                TrackingStateHolder.postMessage("拍照失敗：${error.message ?: error::class.java.simpleName}")
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        !hasCameraPermission -> "允許相機權限"
                        !cameraReady -> "相機準備中…"
                        else -> "拍照"
                    }
                )
            }

            Text(
                "已記錄 ${state.pointCount} 個定位點",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (photoPromptStage != PhotoPromptStage.NONE) {
        val type = if (photoPromptStage == PhotoPromptStage.START) PhotoType.START else PhotoType.END

        fun finishTrack() {
            val finishedTrackId = viewModel.state.value.trackId
            photoPromptStage = PhotoPromptStage.NONE
            viewModel.stopRecording()
            onFinished(finishedTrackId)
        }

        AlertDialog(
            onDismissRequest = { },
            title = { Text(if (type == PhotoType.START) "拍攝出發點照片" else "拍攝終點照片") },
            text = {
                Text(
                    if (!cameraReady) "相機尚未準備完成，可稍候再拍或直接跳過。" else "是否要拍攝這張照片？"
                )
            },
            confirmButton = {
                Button(
                    enabled = cameraReady,
                    onClick = {
                        scope.launch {
                            val result = viewModel.capturePhoto(type)
                            val error = result.exceptionOrNull()
                            if (error != null) {
                                // 拍照失敗時不再靜默關閉對話框：明確顯示原因，讓使用者可以選擇跳過。
                                TrackingStateHolder.postMessage("拍照失敗：${error.message ?: error::class.java.simpleName}")
                                return@launch
                            }
                            if (type == PhotoType.END) {
                                finishTrack()
                            } else {
                                photoPromptStage = PhotoPromptStage.NONE
                            }
                        }
                    }
                ) { Text("拍照") }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    if (type == PhotoType.END) {
                        finishTrack()
                    } else {
                        photoPromptStage = PhotoPromptStage.NONE
                    }
                }) { Text("跳過") }
            }
        )
    }
}

@Composable
private fun TrackMetricsCard(state: com.startinsnow.gpstracker.service.RecordingUiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column {
                Text(
                    formatDuration(state.durationMs),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "持續時間",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                BigMetric("%.2f".format(state.distanceMeters / 1000.0), "距離 (km)")
                BigMetric("%.1f".format(state.currentSpeedKmh), "速度 (km/h)")
                BigMetric(
                    if (state.isStepSensorAvailable) state.stepCount.toString() else "—",
                    "步數"
                )
            }
        }
    }
}

@Composable
private fun BigMetric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
