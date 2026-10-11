package com.startinsnow.gpstracker.ui.settings

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
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.startinsnow.gpstracker.data.prefs.StepSource
import com.startinsnow.gpstracker.sync.GoogleAuthManager

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var offlineMapStatus by remember { mutableStateOf<String?>(null) }
    var offlineDownloadJob by remember { mutableStateOf<Job?>(null) }

    val authManager = remember { GoogleAuthManager(context) }
    var fitStatus by remember { mutableStateOf<String?>(null) }

    val activityPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) fitStatus = "未取得「活動辨識」權限，Google Fit 步數可能無法讀取"
    }

    val fitSignInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        fitStatus = if (task.isSuccessful) {
            "已連接 Google 帳號（含 Google Fit 授權）"
        } else {
            "連接 Google 失敗：${task.exception?.message ?: "未知錯誤"}"
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
                SwitchRow(
                    title = "步數來源：Google Fit",
                    subtitle = "記錄結束時以 Google Fit 讀取該趟步數；未連接時自動改用裝置感測器",
                    checked = settings.stepSource == StepSource.GOOGLE_FIT
                ) { enabled ->
                    viewModel.setStepSource(if (enabled) StepSource.GOOGLE_FIT else StepSource.DEVICE_SENSOR)
                    if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
                    ) {
                        activityPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                    }
                }
                SectionDivider()
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { fitSignInLauncher.launch(authManager.signInIntent()) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("連接 Google Fit") }
                    Text(
                        "需在 Google Cloud 專案啟用 Fitness API，並同意 fitness.activity.read 授權，Google Fit 才會回傳步數。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    fitStatus?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
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
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            singleLine = true,
            visualTransformation = if (isSecret) PasswordVisualTransformation() else VisualTransformation.None
        )
    }
}
