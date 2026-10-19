package com.startinsnow.healthtracker.ui

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import com.startinsnow.healthtracker.cloud.CloudSync
import com.startinsnow.healthtracker.data.repository.HealthRecordRepository
import com.startinsnow.healthtracker.importer.PhotoBatchImporter
import com.startinsnow.healthtracker.importer.CsvImporter
import com.startinsnow.healthtracker.importer.HcSample
import com.startinsnow.healthtracker.importer.HealthConnectImporter
import com.startinsnow.healthtracker.importer.HealthConnectMapping
import com.startinsnow.healthtracker.mijia.MijiaAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ImportSection(records: HealthRecordRepository, cloud: CloudSync) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hc = remember { HealthConnectImporter(context) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var samples by remember { mutableStateOf<List<HcSample>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun scan() {
        busy = true
        message = "掃描中…"
        scope.launch {
            try {
                val found = hc.scan()
                samples = found
                selected = found.map { it.originPackage }.toSet()
                message = if (found.isEmpty()) {
                    "Health Connect 裡沒有體重／體脂率資料。請確認米家或 Mi Fitness 已開啟「同步到 Health Connect」，或改用 CSV／手動輸入。"
                } else {
                    "找到 ${found.size} 筆，來源如下；勾選要匯入的來源。"
                }
            } catch (e: Exception) {
                message = "讀取失敗：${e.message}"
            } finally {
                busy = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (granted.containsAll(HealthConnectImporter.PERMISSIONS)) scan() else message = "沒有授權，無法讀取 Health Connect"
    }

    var batchProgress by remember { mutableStateOf<String?>(null) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                val result = PhotoBatchImporter(context, records).run(uris) { done, total ->
                    batchProgress = "處理中 $done / $total"
                }
                batchProgress = null
                message = result.summary() + "。"
                if (result.saved > 0) {
                    // 先存好在手機，再接著上傳；沒登入就留在手機，登入後會自動上傳
                    val engine = cloud.engine
                    message += if (engine == null || engine.currentSession() == null) {
                        "尚未登入雲端，資料已存在手機，登入後會自動上傳。"
                    } else {
                        "上傳：" + engine.sync().summary()
                    }
                }
            } catch (e: Exception) {
                batchProgress = null
                message = "批次匯入失敗：${e.message}"
            } finally {
                busy = false
            }
        }
    }

    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                }
                if (text == null) {
                    message = "無法讀取檔案"
                } else {
                    val parsed = CsvImporter.parse(text)
                    val result = records.importRecords(parsed.records)
                    message = buildString {
                        append("CSV：新增 ${result.inserted} 筆")
                        if (result.skipped > 0) append("，略過 ${result.skipped} 筆重複")
                        if (parsed.errors.isNotEmpty()) append("\n有問題的列：\n" + parsed.errors.take(5).joinToString("\n"))
                    }
                }
            } catch (e: Exception) {
                message = "匯入失敗：${e.message}"
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("匯入資料", style = MaterialTheme.typography.titleLarge)
        Text("血糖機照片（批次）", style = MaterialTheme.typography.titleMedium)
        Text("一次選多張血糖機照片：自動辨識數值，測量時間依照片拍攝時間，先存在手機，再接著上傳雲端。辨識可能有誤，請到「紀錄」點照片對照確認。", style = MaterialTheme.typography.bodySmall)
        Button(enabled = !busy, onClick = {
            message = null
            photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }) { Text(batchProgress ?: "批次匯入照片") }
        Text("小米體脂計 S400（米家）", style = MaterialTheme.typography.titleMedium)
        MijiaCaptureStatus()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = {
                if (!hc.isAvailable()) {
                    message = "這支手機沒有可用的 Health Connect"
                } else {
                    scope.launch {
                        if (hc.hasPermissions()) scan() else permissionLauncher.launch(HealthConnectImporter.PERMISSIONS)
                    }
                }
            }) { Text("掃描 Health Connect") }
            OutlinedButton(enabled = !busy, onClick = {
                csvPicker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel", "*/*"))
            }) { Text("匯入 CSV") }
        }

        if (samples.isNotEmpty()) {
            HealthConnectMapping.countByOrigin(samples).forEach { (origin, count) ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Checkbox(
                        checked = origin in selected,
                        onCheckedChange = { on -> selected = if (on) selected + origin else selected - origin },
                    )
                    Column {
                        Text(origin, style = MaterialTheme.typography.bodyMedium)
                        Text("$count 筆", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Button(enabled = !busy && selected.isNotEmpty(), onClick = {
                busy = true
                scope.launch {
                    val now = System.currentTimeMillis()
                    val mapped = samples.filter { it.originPackage in selected }.mapNotNull { HealthConnectMapping.toRecord(it, now) }
                    val result = records.importRecords(mapped)
                    message = "新增 ${result.inserted} 筆，略過 ${result.skipped} 筆（已存在或數值不合理）"
                    samples = emptyList()
                    busy = false
                }
            }) { Text("匯入選取的來源") }
        }
        message?.let { Text(it) }
    }
}

private fun isMijiaServiceEnabled(context: android.content.Context): Boolean {
    val me = ComponentName(context, MijiaAccessibilityService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    return enabled.split(':').any { it.equals(me, ignoreCase = true) }
}

/** 米家畫面讀取（無障礙服務）的狀態與開啟入口；開關一定要使用者自己在系統設定打開。 */
@Composable
private fun MijiaCaptureStatus() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(isMijiaServiceEnabled(context)) }
    LifecycleResumeEffect(Unit) {
        enabled = isMijiaServiceEnabled(context) // 從系統設定回來時刷新
        onPauseOrDispose {}
    }
    Text(
        if (enabled) "米家畫面讀取：已開啟。打開米家的「Xiaomi 體脂計 S400」頁面，畫面上的體重與體脂率會自動存入。"
        else "米家畫面讀取：未開啟。開啟後，打開米家的 S400 頁面就會自動擷取體重與體脂率（只對米家有效）。",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("開啟無障礙設定") }
        context.packageManager.getLaunchIntentForPackage(MijiaAccessibilityService.MIJIA_PACKAGE)?.let { mijia ->
            OutlinedButton(onClick = { context.startActivity(mijia) }) { Text("開啟米家") }
        }
    }
}
