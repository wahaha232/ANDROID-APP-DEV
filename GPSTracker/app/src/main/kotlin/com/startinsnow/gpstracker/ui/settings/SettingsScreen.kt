package com.startinsnow.gpstracker.ui.settings

import com.startinsnow.gpstracker.R
import androidx.compose.ui.res.stringResource
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.startinsnow.gpstracker.data.prefs.AutoPauseDelay
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
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var offlineMapStatus by remember { mutableStateOf<String?>(null) }
    var offlineDownloadJob by remember { mutableStateOf<Job?>(null) }

    var hcStatus by remember { mutableStateOf<String?>(null) }
    var hcBackgroundRequested by remember { mutableStateOf(false) }

    val activityPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) hcStatus = context.getString(R.string.settings_001)
    }

    val hcLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        hcStatus = if (granted.contains(HealthConnectStepReader.READ_PERMISSION)) {
            if (hcBackgroundRequested && !granted.contains(HealthConnectStepReader.BACKGROUND_PERMISSION)) {
                context.getString(R.string.settings_002)
            } else {
                context.getString(R.string.settings_003)
            }
        } else {
            context.getString(R.string.settings_004)
        }
    }

    val runHcDiagnostics: () -> Unit = {
        val reader = HealthConnectStepReader(context)
        scope.launch {
            hcStatus = context.getString(R.string.settings_005)
            hcStatus = when {
                !reader.isAvailable() -> context.getString(R.string.settings_006)
                !reader.hasPermission() -> context.getString(R.string.settings_007)
                else -> reader.stepsByOriginLastDays(7).fold(
                    onSuccess = { m ->
                        val bgWarn = if (reader.isBackgroundReadSupported() && !reader.hasBackgroundPermission())
                            context.getString(R.string.settings_008) else ""
                        if (m.isEmpty()) context.getString(R.string.settings_009, bgWarn)
                        else context.getString(R.string.settings_010) + m.entries.joinToString("\n") { context.getString(R.string.settings_011, it.key, it.value) } + bgWarn
                    },
                    onFailure = { context.getString(R.string.settings_012, it.message) }
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
                title = { Text(stringResource(R.string.settings_013), style = MaterialTheme.typography.titleLarge) },
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
            SettingsSection(title = context.getString(R.string.settings_014)) {
                SwitchRow(
                    title = context.getString(R.string.settings_015),
                    subtitle = context.getString(R.string.settings_016),
                    checked = settings.gpsMode == GpsMode.HIGH_ACCURACY
                ) { viewModel.setGpsMode(if (it) GpsMode.HIGH_ACCURACY else GpsMode.ADAPTIVE) }
                SectionDivider()
                SwitchRow(
                    title = context.getString(R.string.settings_017),
                    subtitle = context.getString(R.string.settings_018),
                    checked = settings.autoPauseEnabled
                ) { viewModel.setAutoPause(it) }
                SectionDivider()
                AutoPauseDelayRow(
                    seconds = settings.autoPauseSeconds,
                    enabled = settings.autoPauseEnabled,
                    onSave = { viewModel.setAutoPauseSeconds(it) }
                )
            }

            SettingsSection(title = context.getString(R.string.settings_019)) {
                Text(
                    stringResource(R.string.settings_020),
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
                        label = { Text(stringResource(R.string.settings_021)) }
                    )
                }
                SectionDivider()
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (settings.stepSource) {
                        StepSource.GOOGLE_FIT -> Text(
                            stringResource(R.string.settings_022),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        StepSource.ZEPP_LIFE -> Text(
                            stringResource(R.string.settings_023),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        StepSource.DEVICE_SENSOR -> Text(
                            stringResource(R.string.settings_024),
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
                                    // 讀取授權前的歷史步數（一次同步更久遠的每日步數）
                                    if (HealthConnectStepReader(context).isHistoryReadSupported()) {
                                        permissions += HealthConnectStepReader.HISTORY_PERMISSION
                                    }
                                    hcLauncher.launch(permissions)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.settings_025)) }
                        OutlinedButton(
                            onClick = { runHcDiagnostics() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.settings_026)) }
                        if (!healthConnectAvailable(context)) {
                            Text(
                                stringResource(R.string.settings_027),
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

            SettingsSection(title = context.getString(R.string.settings_028)) {
                SwitchRow(
                    title = context.getString(R.string.settings_029),
                    subtitle = context.getString(R.string.settings_030),
                    checked = settings.driveSyncEnabled
                ) { viewModel.setDriveSync(it) }
                SectionDivider()
                SwitchRow(
                    title = context.getString(R.string.settings_031),
                    subtitle = context.getString(R.string.settings_032),
                    checked = settings.wifiOnlySync
                ) { viewModel.setWifiOnly(it) }
                SectionDivider()
                SwitchRow(
                    title = context.getString(R.string.settings_033),
                    subtitle = context.getString(R.string.settings_034),
                    checked = settings.deleteLocalAfterUploadEnabled
                ) { viewModel.setDeleteAfterUpload(it) }
            }

            SettingsSection(title = context.getString(R.string.settings_035)) {
                SwitchRow(
                    title = context.getString(R.string.settings_036),
                    subtitle = context.getString(R.string.settings_037),
                    checked = settings.offlineMapEnabled
                ) { enabled ->
                    viewModel.setOfflineMap(enabled)
                    // 先取消舊的下載，避免反覆開關時同時跑多個 collector 而重複下載。
                    offlineDownloadJob?.cancel()
                    if (enabled) {
                        offlineMapStatus = context.getString(R.string.settings_038)
                        offlineDownloadJob = scope.launch {
                            OfflineMapManager.downloadDefaultRegion(context).collect { progress ->
                                offlineMapStatus = when (progress) {
                                    is OfflineDownloadProgress.InProgress -> context.getString(R.string.settings_039, progress.percent)
                                    is OfflineDownloadProgress.Completed -> context.getString(R.string.settings_040)
                                    is OfflineDownloadProgress.Failed -> context.getString(R.string.settings_041, progress.reason)
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

            SettingsSection(title = context.getString(R.string.settings_042)) {
                Text(
                    stringResource(
                        R.string.settings_043,
                        if (settings.dataRetentionDays == 0) stringResource(R.string.settings_retention_forever)
                        else stringResource(R.string.settings_retention_days, settings.dataRetentionDays)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                if (settings.dataRetentionDays != 0) {
                    Text(
                        if (settings.driveSyncEnabled) {
                            stringResource(R.string.settings_044)
                        } else {
                            stringResource(R.string.settings_045)
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
                            label = { Text(if (days == 0) stringResource(R.string.settings_046) else stringResource(R.string.settings_047, days)) }
                        )
                    }
                }
            }

            SettingsSection(title = context.getString(R.string.backup_title)) {
                val appContext = context.applicationContext as com.startinsnow.gpstracker.GpsTrackerApplication
                var busy by remember { mutableStateOf(false) }
                var backupMessage by remember { mutableStateOf<String?>(null) }
                var pendingRestore by remember { mutableStateOf<Pair<android.net.Uri, Int>?>(null) }
                val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) scope.launch {
                        busy = true
                        backupMessage = null
                        com.startinsnow.gpstracker.data.backup.BackupManager.inspect(appContext, uri)
                            .onSuccess { pendingRestore = uri to it.photoCount }
                            .onFailure { backupMessage = context.getString(R.string.backup_invalid, it.message ?: it::class.java.simpleName) }
                        busy = false
                    }
                }
                Text(
                    stringResource(R.string.backup_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                backupMessage = context.getString(R.string.backup_exporting)
                                runCatching { com.startinsnow.gpstracker.data.backup.BackupManager.export(appContext) }
                                    .onSuccess {
                                        backupMessage = null
                                        com.startinsnow.gpstracker.data.backup.BackupManager.share(context, it)
                                    }
                                    .onFailure { backupMessage = context.getString(R.string.backup_export_failed, it.message ?: it::class.java.simpleName) }
                                busy = false
                            }
                        }
                    ) { Text(stringResource(R.string.backup_export)) }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { pickBackup.launch(arrayOf("application/zip", "application/octet-stream")) }
                    ) { Text(stringResource(R.string.backup_import)) }
                }
                backupMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                pendingRestore?.let { (uri, photoCount) ->
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { pendingRestore = null },
                        title = { Text(stringResource(R.string.backup_confirm_title)) },
                        text = { Text(stringResource(R.string.backup_confirm_body, photoCount)) },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                pendingRestore = null
                                scope.launch {
                                    busy = true
                                    com.startinsnow.gpstracker.data.backup.BackupManager.restore(appContext, uri)
                                        .onSuccess { com.startinsnow.gpstracker.data.backup.BackupManager.restartApp(appContext) }
                                        .onFailure { backupMessage = context.getString(R.string.backup_restore_failed, it.message ?: it::class.java.simpleName) }
                                    busy = false
                                }
                            }) { Text(stringResource(R.string.backup_confirm_ok), color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { pendingRestore = null }) {
                                Text(stringResource(R.string.backup_cancel))
                            }
                        }
                    )
                }
            }

            SettingsSection(title = context.getString(R.string.privacy_title)) {
                var radius by remember { mutableStateOf(com.startinsnow.gpstracker.core.privacy.PrivacyZones.DEFAULT_RADIUS) }
                var privacyMessage by remember { mutableStateOf<String?>(null) }
                Text(
                    stringResource(R.string.privacy_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                settings.privacyZones.forEachIndexed { i, z ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📍 %.4f, %.4f・%d m".format(z.latitude, z.longitude, z.radiusMeters), style = MaterialTheme.typography.bodyMedium)
                        androidx.compose.material3.TextButton(onClick = { viewModel.removePrivacyZone(i) }) { Text(stringResource(R.string.privacy_remove)) }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(100, 200, 500, 1000).forEach { r ->
                        FilterChip(selected = radius == r, onClick = { radius = r }, label = { Text("$r m") })
                    }
                }
                OutlinedButton(
                    onClick = {
                        viewModel.addPrivacyZoneHere(radius) { ok ->
                            privacyMessage = context.getString(if (ok) R.string.privacy_added else R.string.privacy_no_location)
                        }
                    },
                    enabled = settings.privacyZones.size < com.startinsnow.gpstracker.core.privacy.PrivacyZones.MAX_ZONES,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                ) { Text(stringResource(R.string.privacy_add_here)) }
                privacyMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }

            SettingsSection(title = context.getString(R.string.diag_title)) {
                var crashCount by remember { mutableStateOf(com.startinsnow.gpstracker.diagnostics.CrashLogger.count(context)) }
                Text(
                    if (crashCount == 0) stringResource(R.string.diag_none) else stringResource(R.string.diag_count, crashCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                if (crashCount > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = { com.startinsnow.gpstracker.diagnostics.CrashLogger.shareLatest(context) }) {
                            Text(stringResource(R.string.diag_share))
                        }
                        OutlinedButton(onClick = {
                            com.startinsnow.gpstracker.diagnostics.CrashLogger.clear(context)
                            crashCount = 0
                        }) { Text(stringResource(R.string.diag_clear)) }
                    }
                }
            }

            SettingsSection(title = context.getString(R.string.settings_048)) {
                SwitchRow(
                    title = context.getString(R.string.settings_049),
                    subtitle = context.getString(R.string.settings_050),
                    checked = settings.webUploadEnabled
                ) { viewModel.setWebUpload(it) }
                SectionDivider()
                SettingsTextField(context.getString(R.string.settings_051), settings.webRepo) { viewModel.setWebRepo(it) }
                SettingsTextField(context.getString(R.string.settings_052), settings.webBranch) { viewModel.setWebBranch(it) }
                SettingsTextField(context.getString(R.string.settings_053), settings.webDataPath) { viewModel.setWebDataPath(it) }
                SettingsTextField(context.getString(R.string.settings_054), settings.githubToken, isSecret = true) { viewModel.setGithubToken(it) }
                SectionDivider()
                SwitchRow(
                    title = context.getString(R.string.settings_031),
                    subtitle = context.getString(R.string.settings_032),
                    checked = settings.webWifiOnly
                ) { viewModel.setWebWifiOnly(it) }
                Text(
                    stringResource(R.string.settings_055),
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

/** 「靜止多久後自動暫停」：以 時／分／秒 顯示目前設定，點「修改」開啟輸入對話框。 */
@Composable
private fun AutoPauseDelayRow(seconds: Int, enabled: Boolean, onSave: (Int) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                stringResource(R.string.autopause_delay_title),
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                autoPauseDelayText(seconds),
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedButton(onClick = { showDialog = true }, enabled = enabled) { Text(stringResource(R.string.autopause_delay_edit)) }
    }
    if (showDialog) {
        AutoPauseDelayDialog(
            initialSeconds = seconds,
            onDismiss = { showDialog = false },
            onSave = { showDialog = false; onSave(it) }
        )
    }
}

@Composable
private fun AutoPauseDelayDialog(initialSeconds: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    val initial = AutoPauseDelay.toHms(initialSeconds)
    var hours by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initial.hours.toString()) }
    var minutes by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initial.minutes.toString()) }
    var secs by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initial.seconds.toString()) }
    // 欄位留空視為 0；只接受數字、最多 5 位，避免溢位。
    val total = AutoPauseDelay.totalSeconds(hours.toLongOrNull() ?: 0L, minutes.toLongOrNull() ?: 0L, secs.toLongOrNull() ?: 0L)
    val valid = AutoPauseDelay.isValid(total)

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.autopause_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.autopause_dialog_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HmsField(hours, { hours = it }, stringResource(R.string.autopause_label_hours), Modifier.weight(1f))
                    HmsField(minutes, { minutes = it }, stringResource(R.string.autopause_label_minutes), Modifier.weight(1f))
                    HmsField(secs, { secs = it }, stringResource(R.string.autopause_label_seconds), Modifier.weight(1f))
                }
                if (valid) {
                    Text(
                        autoPauseDelayText(total.toInt()),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        stringResource(R.string.autopause_range_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = valid, onClick = { onSave(total.toInt()) }) {
                Text(stringResource(R.string.autopause_save))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.autopause_cancel)) }
        }
    )
}

@Composable
private fun HmsField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(5)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
        modifier = modifier
    )
}

/** 例如 90 秒 -> 「1 分 30 秒」；3600 秒 -> 「1 時」。 */
@Composable
private fun autoPauseDelayText(seconds: Int): String {
    val t = AutoPauseDelay.toHms(seconds)
    val parts = buildList {
        if (t.hours > 0) add(stringResource(R.string.autopause_fmt_hours, t.hours))
        if (t.minutes > 0) add(stringResource(R.string.autopause_fmt_minutes, t.minutes))
        if (t.seconds > 0) add(stringResource(R.string.autopause_fmt_seconds, t.seconds))
    }
    return parts.joinToString(" ")
}
