package com.startinsnow.gpstracker.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.startinsnow.gpstracker.data.prefs.GpsMode
import com.startinsnow.gpstracker.ui.map.OfflineDownloadProgress
import com.startinsnow.gpstracker.ui.map.OfflineMapManager
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var offlineMapStatus by remember { mutableStateOf<String?>(null) }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text("設定", style = MaterialTheme.typography.headlineSmall)

            SettingRow("高精度 GPS 模式（較耗電）", settings.gpsMode == GpsMode.HIGH_ACCURACY) {
                viewModel.setGpsMode(if (it) GpsMode.HIGH_ACCURACY else GpsMode.ADAPTIVE)
            }
            SettingRow("自動暫停（靜止超過 1 分鐘）", settings.autoPauseEnabled) { viewModel.setAutoPause(it) }
            SettingRow("啟用 Google Drive 同步", settings.driveSyncEnabled) { viewModel.setDriveSync(it) }
            SettingRow("僅限 Wi-Fi 上傳", settings.wifiOnlySync) { viewModel.setWifiOnly(it) }
            SettingRow("上傳成功後刪除本機資料", settings.deleteLocalAfterUploadEnabled) { viewModel.setDeleteAfterUpload(it) }
            SettingRow("離線地圖（下載台灣本島區域）", settings.offlineMapEnabled) { enabled ->
                viewModel.setOfflineMap(enabled)
                if (enabled) {
                    scope.launch {
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
            offlineMapStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            Text("本機資料保留：${if (settings.dataRetentionDays == 0) "永久保留" else "${settings.dataRetentionDays} 天"}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 30, 90, 180).forEach { days ->
                    androidx.compose.material3.OutlinedButton(onClick = { viewModel.setRetentionDays(days) }) {
                        Text(if (days == 0) "永久" else "$days 天")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
