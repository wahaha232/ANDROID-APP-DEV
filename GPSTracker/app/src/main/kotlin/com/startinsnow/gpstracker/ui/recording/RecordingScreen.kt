package com.startinsnow.gpstracker.ui.recording

import com.startinsnow.gpstracker.core.model.MovementMode
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.horizontalScroll
import com.startinsnow.gpstracker.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class PhotoPromptStage { NONE, START, END }

/** 每次按下縮放鍵的倍率變化量。 */
private const val ZOOM_STEP = 1.5f

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
    val state by viewModel.state.collectAsStateWithLifecycle()

    var hasFineLocation by remember { mutableStateOf(context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) }
    var hasCoarseLocation by remember { mutableStateOf(context.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) }
    var hasCameraPermission by remember { mutableStateOf(context.hasPermission(Manifest.permission.CAMERA)) }
    var cameraReady by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var photoPromptStage by rememberSaveable { mutableStateOf(PhotoPromptStage.NONE) }
    // 準備階段：手動指定的移動方式（null = 自動，由移動速度判斷）；按下「開始記錄」才真正開始。
    var selectedModeName by rememberSaveable { mutableStateOf<String?>(null) }
    var startPressed by rememberSaveable { mutableStateOf(false) }
    // 沒有定位權限時按「開始記錄」：先要權限，授權完成後自動接著開始（不必再按一次）。
    var startAfterPermission by remember { mutableStateOf(false) }
    val pendingPhotos by viewModel.pendingPhotos.collectAsStateWithLifecycle()
    // 本畫面是否已經送出過「開始記錄」：之後（例如從通知列按「結束」再回到 App）ON_RESUME 不可再自動開新一筆。
    var hasRequestedStart by rememberSaveable { mutableStateOf(false) }

    // 變焦（縮放）：直接 collect TrackPhotoManager 的即時變焦狀態（由 CameraX zoomState 驅動）。
    val cameraZoom by viewModel.photoManager.zoom.collectAsStateWithLifecycle()
    // 拍照快門閃光：counter 遞增會觸發一次短暫白畫面，讓使用者知道「按下去了」。
    var flashCounter by remember { mutableIntStateOf(0) }
    var flashAlpha by remember { mutableFloatStateOf(0f) }
    // 對話框內的錯誤（相機未就緒 / 拍照失敗），就近顯示，不依賴畫面頂端的橫幅。
    var dialogError by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    val hasLocationPermission = hasFineLocation || hasCoarseLocation

    // 準備階段：還沒開始記錄（也不是在接續未完成記錄）。此時可以拍照、選移動方式，按「開始記錄」才開始。
    val isActiveRecording = state.status == TrackStatus.RECORDING || state.status == TrackStatus.PAUSED || state.isStarting
    val isPrepare = resumeTrackId == null && !isActiveRecording && !startPressed

    /** 綁定預覽 + 拍照 use case；失敗時留下可顯示的錯誤。 */
    fun bindPreview(previewView: PreviewView) {
        cameraReady = false
        viewModel.photoManager.bindCamera(lifecycleOwner, previewView) { result ->
            cameraReady = result.isSuccess
            cameraError = result.exceptionOrNull()?.let {
                context.getString(R.string.recording_001, it.message ?: it::class.java.simpleName)
            }
        }
    }

    /** 以目前倍率為基準放大（factor > 1）/ 縮小（factor < 1）。 */
    fun changeZoom(factor: Float) {
        viewModel.photoManager.zoomBy(factor)
    }

    /** 拍照的唯一入口：觸發閃光並回傳結果，UI 各處（主畫面 / 對話框）共用同一條路徑。 */
    suspend fun takePhoto(type: PhotoType): Result<Unit> {
        flashCounter++
        return viewModel.capturePhoto(type)
    }

    /** 只有「確實需要」才啟動記錄：權限不足時先要權限，避免同時要權限又啟動造成競態。 */
    fun startOrResumeRecording() {
        // 新的記錄一律由使用者按「開始記錄」才開始；只有「接續未完成記錄」才會自動啟動。
        if (resumeTrackId == null) return
        val current = viewModel.state.value
        if (current.status == TrackStatus.RECORDING || current.status == TrackStatus.PAUSED || current.isStarting) return
        hasRequestedStart = true
        viewModel.startResumeRecording(resumeTrackId)
    }

    val backgroundLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 背景定位為加分項，被拒絕仍可在前景持續記錄，不阻擋核心功能。 */ }

    // 單一權限請求入口：同時 launch 兩個權限請求會讓 Android 的結果回呼互相干擾
    // （會造成相機權限永遠拿不到 → 沒有預覽 → 拍照永遠失敗）。
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // 只在「這次剛取得定位權限」時才接著要背景定位；只按「允許相機」不該連帶跳出背景定位請求。
        val hadLocationBefore = hasFineLocation || hasCoarseLocation
        hasFineLocation = results[Manifest.permission.ACCESS_FINE_LOCATION]
            ?: context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        hasCoarseLocation = results[Manifest.permission.ACCESS_COARSE_LOCATION]
            ?: context.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        hasCameraPermission = results[Manifest.permission.CAMERA]
            ?: context.hasPermission(Manifest.permission.CAMERA)
        if (hasCameraPermission) {
            cameraError = null
        } else {
            cameraError = context.getString(R.string.recording_002)
        }

        // 必須用「剛更新過的 state 變數」重新判斷，不可用組合期算出的 hasLocationPermission 值，
        // 否則剛授權成功時可能被誤判成沒有權限（該 val 是舊一次組合的值）。
        if (!(hasFineLocation || hasCoarseLocation)) {
            startAfterPermission = false
            TrackingStateHolder.postMessage(context.getString(R.string.recording_003))
        } else {
            startOrResumeRecording()
            if (!hadLocationBefore && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                !context.hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            ) {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }

    /** 準備畫面的「開始記錄」：沒有定位權限就先要權限；有的話才真正啟動記錄服務。 */
    fun startNewRecording() {
        if (!(hasFineLocation || hasCoarseLocation)) {
            startAfterPermission = true
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
            return
        }
        startAfterPermission = false
        startPressed = true
        hasRequestedStart = true
        viewModel.startRecording(selectedModeName?.let { runCatching { MovementMode.valueOf(it) }.getOrNull() })
    }

    LaunchedEffect(hasFineLocation, hasCoarseLocation, startAfterPermission) {
        if (startAfterPermission && (hasFineLocation || hasCoarseLocation)) startNewRecording()
    }

    LaunchedEffect(Unit) {
        val missing = buildList {
            if (!hasFineLocation && !hasCoarseLocation) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (!hasCameraPermission) add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                !context.hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            ) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
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

    // 啟動失敗（服務回報訊息、狀態沒進入記錄中）時，回到準備畫面讓使用者可以再按一次。
    LaunchedEffect(state.message) {
        val active = state.status == TrackStatus.RECORDING || state.status == TrackStatus.PAUSED || state.isStarting
        if (state.message != null && !active) startPressed = false
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
                // 只在「還沒開始過」時補啟動（例如先拒絕、到系統設定授權後回來）；
                // 已經開始並結束的畫面不可因為回到前景又自動開始新記錄。
                if ((hasFineLocation || hasCoarseLocation) && !hasRequestedStart) {
                    startOrResumeRecording()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 每次按下快門時短暫顯示白畫面，讓使用者確定「有按下」。
    LaunchedEffect(flashCounter) {
        if (flashCounter == 0) return@LaunchedEffect
        flashAlpha = 0.85f
        delay(150)
        flashAlpha = 0f
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            isPrepare -> stringResource(R.string.recording_prepare_title)
                            state.status == TrackStatus.PAUSED -> stringResource(R.string.recording_004)
                            else -> stringResource(R.string.recording_005)
                        },
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.recording_006))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                if (isPrepare) {
                    Button(
                        onClick = { startNewRecording() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .height(56.dp)
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.recording_start_button), style = MaterialTheme.typography.titleMedium)
                    }
                } else Row(
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
                        Text(if (state.status == TrackStatus.PAUSED) stringResource(R.string.recording_007) else stringResource(R.string.detail_039))
                    }
                    Button(
                        onClick = { photoPromptStage = PhotoPromptStage.END },
                        modifier = Modifier.weight(1f).height(54.dp)
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.recording_008))
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
            if (isPrepare) {
                InfoBanner(
                    text = stringResource(R.string.recording_prepare_hint),
                    color = MaterialTheme.colorScheme.primary
                )
                ModeSelectorCard(selectedName = selectedModeName, onSelect = { selectedModeName = it })
            } else {
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
            }

            if (state.isAutoPaused) {
                InfoBanner(
                    text = context.getString(R.string.recording_009),
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            if (!hasLocationPermission) {
                InfoBanner(
                    text = context.getString(R.string.recording_010),
                    color = MaterialTheme.colorScheme.error,
                    icon = Icons.Filled.ErrorOutline
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            permissionLauncher.launch(
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.recording_011)) }
                    // 被「永久拒絕」後系統不會再跳出授權視窗，只能請使用者到系統設定手動開啟。
                    OutlinedButton(
                        onClick = { openAppSettings(context) },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.recording_012)) }
                }
            }

            state.message?.let { message ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoBanner(text = message, color = MaterialTheme.colorScheme.error, icon = Icons.Filled.ErrorOutline)
                    OutlinedButton(
                        onClick = { TrackingStateHolder.clearMessage() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.recording_013)) }
                }
            }

            if (hasCameraPermission) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(Color.Black)
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            PreviewView(ctx).also { previewView -> bindPreview(previewView) }
                        }
                    )

                    // 快門白畫面回饋
                    if (flashAlpha > 0f) {
                        Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flashAlpha)))
                    }

                    // 相機狀態（準備中 / 錯誤），就近顯示在預覽畫面上
                    val statusLabel = when {
                        cameraReady -> null
                        cameraError != null -> cameraError
                        else -> context.getString(R.string.recording_014)
                    }
                    if (statusLabel != null) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.55f),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.align(Alignment.Center)
                        ) {
                            Text(
                                statusLabel,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                            )
                        }
                    }

                    // 縮放鍵（右上角）
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledIconButton(
                            onClick = { changeZoom(ZOOM_STEP) },
                            enabled = cameraReady && cameraZoom.ratio < cameraZoom.maxRatio - 0.001f,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Filled.ZoomIn, contentDescription = stringResource(R.string.recording_015))
                        }
                        Surface(
                            color = Color.Black.copy(alpha = 0.55f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                "%.1fx".format(cameraZoom.ratio),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        FilledIconButton(
                            onClick = { changeZoom(1f / ZOOM_STEP) },
                            enabled = cameraReady && cameraZoom.ratio > cameraZoom.minRatio + 0.001f,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Filled.ZoomOut, contentDescription = stringResource(R.string.recording_016))
                        }
                    }

                    // 快門鍵（底部中央）
                    FilledIconButton(
                        onClick = {
                            scope.launch {
                                val error = takePhoto(PhotoType.NORMAL).exceptionOrNull()
                                if (error == null) {
                                    snackbarHostState.showSnackbar(context.getString(R.string.recording_017))
                                } else {
                                    snackbarHostState.showSnackbar(
                                        context.getString(R.string.recording_018, error.message ?: error::class.java.simpleName)
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp)
                            .size(68.dp)
                    ) {
                        Icon(
                            Icons.Filled.PhotoCamera,
                            contentDescription = stringResource(R.string.recording_019),
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
            } else {
                OutlinedButton(
                    onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA)) },
                    modifier = Modifier.fillMaxWidth().height(54.dp)
                ) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.recording_020))
                }
                // 相機權限被永久拒絕時，上面的按鈕不會再跳出授權視窗，提供直接前往系統設定的出口。
                androidx.compose.material3.TextButton(onClick = { openAppSettings(context) }) {
                    Text(stringResource(R.string.recording_021))
                }
            }

            Text(
                if (isPrepare) stringResource(R.string.recording_pending_photos, pendingPhotos.size)
                else stringResource(R.string.recording_022, state.pointCount),
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

        // 每次開啟對話框都清掉上一次的錯誤訊息。
        LaunchedEffect(photoPromptStage) { dialogError = null }

        AlertDialog(
            onDismissRequest = { },
            title = { Text(if (type == PhotoType.START) stringResource(R.string.recording_023) else stringResource(R.string.recording_024)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (!cameraReady) stringResource(R.string.recording_025) else stringResource(R.string.recording_026)
                    )
                    dialogError?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                // 永遠可按：即使相機還沒好也會顯示原因，避免「按了卻完全沒反應」。
                Button(
                    onClick = {
                        if (!cameraReady) {
                            dialogError = cameraError ?: context.getString(R.string.recording_027)
                            return@Button
                        }
                        scope.launch {
                            val result = takePhoto(type)
                            result.onSuccess {
                                dialogError = null
                                if (type == PhotoType.END) {
                                    finishTrack()
                                } else {
                                    photoPromptStage = PhotoPromptStage.NONE
                                }
                            }.onFailure { error ->
                                dialogError = context.getString(R.string.recording_018, error.message ?: error::class.java.simpleName)
                            }
                        }
                    }
                ) { Text(stringResource(R.string.recording_019)) }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    if (type == PhotoType.END) {
                        finishTrack()
                    } else {
                        photoPromptStage = PhotoPromptStage.NONE
                    }
                }) { Text(stringResource(R.string.recording_028)) }
            }
        )
    }
}

/** 手動移動方式（不含「靜止」；「其他」對應 UNKNOWN）。第一個選項「自動」代表由移動速度判斷。 */
private val MANUAL_MODES = listOf(
    MovementMode.WALKING, MovementMode.RUNNING, MovementMode.BICYCLE, MovementMode.MOTORCYCLE,
    MovementMode.CAR, MovementMode.PUBLIC_TRANSPORT, MovementMode.AIRPLANE, MovementMode.UNKNOWN
)

private fun modeLabelRes(mode: MovementMode): Int = when (mode) {
    MovementMode.WALKING -> R.string.mode_walking
    MovementMode.RUNNING -> R.string.mode_running
    MovementMode.BICYCLE -> R.string.mode_bicycle
    MovementMode.MOTORCYCLE -> R.string.mode_motorcycle
    MovementMode.CAR -> R.string.mode_car
    MovementMode.PUBLIC_TRANSPORT -> R.string.mode_public_transport
    MovementMode.AIRPLANE -> R.string.mode_airplane
    else -> R.string.mode_other
}

@Composable
private fun ModeSelectorCard(selectedName: String?, onSelect: (String?) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.recording_mode_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.recording_mode_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedName == null,
                    onClick = { onSelect(null) },
                    label = { Text(stringResource(R.string.recording_mode_auto)) }
                )
                MANUAL_MODES.forEach { mode ->
                    FilterChip(
                        selected = selectedName == mode.name,
                        onClick = { onSelect(mode.name) },
                        label = { Text(mode.emoji + " " + stringResource(modeLabelRes(mode))) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackMetricsCard(state: com.startinsnow.gpstracker.service.RecordingUiState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column {
                Text(
                    formatDuration(state.durationMs),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.detail_013),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                BigMetric("%.2f".format(state.distanceMeters / 1000.0), context.getString(R.string.recording_029))
                BigMetric("%.1f".format(state.currentSpeedKmh), context.getString(R.string.recording_030))
                BigMetric(
                    if (state.isStepSensorAvailable) state.stepCount.toString() else "—",
                    context.getString(R.string.settings_019)
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

/** 開啟本 App 的系統設定頁，讓使用者手動開啟被永久拒絕的權限。 */
private fun openAppSettings(context: Context) {
    val intent = android.content.Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.fromParts("package", context.packageName, null)
    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
