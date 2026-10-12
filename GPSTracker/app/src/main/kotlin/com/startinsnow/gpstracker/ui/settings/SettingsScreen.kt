package com.startinsnow.gpstracker.ui.settings

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.startinsnow.gpstracker.data.prefs.GpsMode
import com.startinsnow.gpstracker.ui.components.SectionHeader
import com.startinsnow.gpstracker.ui.map.OfflineDownloadProgress
import com.startinsnow.gpstracker.ui.map.OfflineMapManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.OutlinedButton
import androidx.core.content.ContextCompat
import com.startinsnow.gpstracker.data.prefs.StepSource
import com.startinsnow.gpstracker.sync.HealthConnectStepReader
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var offlineMapStatus by remember { mutableStateOf<String?>(null) }
    var offlineDownloadJob by remember { mutableStateOf<Job?>(null) }

    var hcStatus by remember { mutableStateOf<String?>(null) }
    var hcBackgroundRequested by remember { mutableStateOf(false) }

    val activityPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) hcStatus = "未取得「活動辨識」權限，Health Connect 步數可能無法讀取"
    }

    val hcLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        hcStatus = if (granted.contains(HealthConnectStepReader.READ_PERMISSION)) {
            if (hcBackgroundRequested && !granted.contains(HealthConnectStepReader.BACKGROUND_PERMISSION)) {
                "已授權讀取步數，但未授權「背景讀取」：錄製結束時 App 不在前景，可能讀不到 Zepp Life／Google Fit 步數（會改用手機感測器）。"
            } else {
                "已授權 Health Connect 讀取步數"
            }
        } else {
            "未授權 Health Connect 讀取步數"
        }
    }

    val runHcDiagnostics: () -> Unit = {
        val reader = HealthConnectStepReader(context)
        scope.launch {
            hcStatus = "檢查中…"
            hcStatus = when {
                !reader.isAvailable() -> "此裝置未提供 Health Connect。"
                !reader.hasPermission() -> "尚未授權 Health Connect 讀取步數，請先按上方授權。"
                else -> reader.stepsByOriginLastDays(7).fold(
                    onSuccess = { m ->
                        val bgWarn = if (reader.isBackgroundReadSupported() && !reader.hasBackgroundPermission())
                            "\n⚠ 尚未授權「背景讀取」，錄製結束時可能讀不到步數，請再按上方授權。" else ""
                        if (m.isEmpty()) "近 7 天 Health Connect 沒有任何步數資料（請確認來源 App 已同步到 Health Connect）。$bgWarn"
                        else "近 7 天步數來源：\n" + m.entries.joinToString("\n") { "• ${it.key}：${it.value} 步" } + bgWarn
                    },
                    onFailure = { "檢查失敗：${it.message}" }
                )
            }
        }
    }

    // 離開設定畫面時取消仍在進行的離線地圖下載，避免背景 collector 洩漏。
    DisposableEffect(Unit) {
        onDispose { offlineDownloadJob?.cancel() }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("設定", style = MaterialTheme.typography.titleLarge) },
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
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            SettingsSection(title = "記錄") {
                SwitchRow(
                    title = "高精度 GPS 模式",
                    subtitle = "提高定位更新頻率，較耗電",
                    checked = settings.gpsMode == GpsMode.HIGH_ACCURACY
                ) { viewModel.setGpsMode(if (it) GpsMode.HIGH_ACCURACY else GpsMode.ADAPTIVE) }
                SectionDivider()
                SwitchRow(
                    title = "自動暫停",
                    subtitle = "靜止超過 1 分鐘後自動暫停記錄",
                    checked = settings.autoPauseEnabled
                ) { viewModel.setAutoPause(it) }
            }

            SettingsSection(title = "步數") {
                Text(
                    "記錄結束時讀取該趟步數；讀取失敗或未授權時自動改用裝置感測器。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = settings.stepSource == StepSource.GOOGLE_FIT,
                        onClick = {
                            viewModel.setStepSource(StepSource.GOOGLE_FIT)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
                            ) {
                                activityPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                            }
                        },
                        label = { Text("Google Fit") }
                    )
                    FilterChip(
                        selected = settings.stepSource == StepSource.ZEPP_LIFE,
                        onClick = {
                            viewModel.setStepSource(StepSource.ZEPP_LIFE)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
                            ) {
                                activityPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                            }
                        },
                        label = { Text("小米 Zepp Life") }
                    )
                }
                SectionDivider()
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (settings.stepSource) {
                        StepSource.GOOGLE_FIT -> Text(
                            "Google Fit 的步數會同步到 Health Connect（免 Google OAuth）；本 App 會讀取 Google Fit 來源的步數。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        StepSource.ZEPP_LIFE -> Text(
                            "小米 Zepp Life / Mi Fitness 的步數會同步到 Health Connect；請先在 Zepp Life 開啟同步。本 App 會讀取 Zepp Life／Mi Fitness 來源的步數。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        StepSource.DEVICE_SENSOR -> Text(
                            "使用手機內建步數感測器（最省電、不需額外授權）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (settings.stepSource != StepSource.DEVICE_SENSOR) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val permissions = mutableSetOf(HealthConnectStepReader.READ_PERMISSION)
                                    hcBackgroundRequested = HealthConnectStepReader(context).isBackgroundReadSupported()
                                    if (hcBackgroundRequested) permissions += HealthConnectStepReader.BACKGROUND_PERMISSION
                                    hcLauncher.launch(permissions)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("授權 Health Connect 讀取步數") }
                        OutlinedButton(
                            onClick = { runHcDiagnostics() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("檢查 Health Connect 資料") }
                        if (!healthConnectAvailable(context)) {
                            Text(
                                "此裝置未偵測到 Health Connect，請先安裝並啟用。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        hcStatus?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            SettingsSection(title = "雲端同步") {
                SwitchRow(
                    title = "啟用 Google Drive 同步",
                    subtitle = "將軌跡打包上傳到你的 Google Drive",
                    checked = settings.driveSyncEnabled
                ) { viewModel.setDriveSync(it) }
                SectionDivider()
                SwitchRow(
                    title = "僅限 Wi-Fi 上傳",
                    subtitle = "避免使用行動網路流量",
                    checked = settings.wifiOnlySync
                ) { viewModel.setWifiOnly(it) }
                SectionDivider()
                SwitchRow(
                    title = "上傳成功後刪除本機資料",
                    subtitle = "成功上傳後清空裝置上的該筆紀錄",
                    checked = settings.deleteLocalAfterUploadEnabled
                ) { viewModel.setDeleteAfterUpload(it) }
            }

            SettingsSection(title = "離線地圖") {
                SwitchRow(
                    title = "下載離線地圖",
                    subtitle = "下載台灣本島區域，無網路時仍可顯示地圖",
                    checked = settings.offlineMapEnabled
                ) { enabled ->
                    viewModel.setOfflineMap(enabled)
                    // 先取消舊的下載，避免反覆開關時同時跑多個 collector 而重複下載。
                    offlineDownloadJob?.cancel()
                    if (enabled) {
                        offlineMapStatus = "下載中… 0%"
                        offlineDownloadJob = scope.launch {
                            OfflineMapManager.downloadDefaultRegion(context).collect { progress ->
                                offlineMapStatus = when (progress) {
                                    is OfflineDownloadProgress.InProgress -> "下載中… ${progress.percent}%"
                                    is OfflineDownloadProgress.Completed -> "離線地圖下載完成"
                                    is OfflineDownloadProgress.Failed -> "下載失敗：${progress.reason}"
                                }
                            }
                        }
                    } else {
                        offlineMapStatus = null
                    }
                }
                offlineMapStatus?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }

            SettingsSection(title = "資料保留") {
                Text(
                    "本機資料保留：${if (settings.dataRetentionDays == 0) "永久保留" else "${settings.dataRetentionDays} 天"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                if (settings.dataRetentionDays != 0) {
                    Text(
                        if (settings.driveSyncEnabled) {
                            "已開啟 Google Drive 同步：只會自動清理「已成功備份」的紀錄，尚未備份的不會被刪除。"
                        } else {
                            "⚠ 尚未開啟 Google Drive 同步：超過天數的紀錄會被永久刪除且無備份。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (settings.driveSyncEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(0, 30, 90, 180).forEach { days ->
                        FilterChip(
                            selected = settings.dataRetentionDays == days,
                            onClick = { viewModel.setRetentionDays(days) },
                            label = { Text(if (days == 0) "永久" else "$days 天") }
                        )
                    }
                }
            }

            SettingsSection(title = "網頁儀表板（GitHub Pages）") {
                SwitchRow(
                    title = "自動上傳到網頁",
                    subtitle = "記錄結束後，把該筆紀錄上傳到 GitHub repo 供網頁讀取",
                    checked = settings.webUploadEnabled
                ) { viewModel.setWebUpload(it) }
                SectionDivider()
                SettingsTextField("GitHub 儲存庫 (owner/repo)", settings.webRepo) { viewModel.setWebRepo(it) }
                SettingsTextField("分支", settings.webBranch) { viewModel.setWebBranch(it) }
                SettingsTextField("資料夾路徑", settings.webDataPath) { viewModel.setWebDataPath(it) }
                SettingsTextField("GitHub Token（fine-grained PAT）", settings.githubToken, isSecret = true) { viewModel.setGithubToken(it) }
                SectionDivider()
                SwitchRow(
                    title = "僅限 Wi-Fi 上傳",
                    subtitle = "避免使用行動網路流量",
                    checked = settings.webWifiOnly
                ) { viewModel.setWebWifiOnly(it) }
                Text(
                    "設定好儲存庫、分支、路徑與 token 後，每次記錄結束會自動上傳。\n網頁網址：https://wahaha232.github.io/ANDROID-APP-DEV/gpstracker-web/",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(title = title)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column { content() }
        }
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsTextField(
    title: String,
    value: String,
    isSecret: Boolean = false,
    onValueChange: (String) -> Unit
) {
    // 欄位以本地狀態顯示：設定值經 DataStore 非同步寫入、再（對 Token 還要經 Keystore 解密）回流，
    // 若直接拿回流值當 value，快速輸入時會掉字、游標跳動。編輯中（有焦點）不接受外部值覆蓋，
    // 失去焦點或首次載入時才與已儲存的值同步。
    var text by remember { mutableStateOf(value) }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(value, focused) { if (!focused && text != value) text = value }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                onValueChange(it)
            },
            interactionSource = interactionSource,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            singleLine = true,
            visualTransformation = if (isSecret) PasswordVisualTransformation() else VisualTransformation.None
        )
    }
}

private fun healthConnectAvailable(context: android.content.Context): Boolean =
    runCatching { HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE }.getOrDefault(false)
