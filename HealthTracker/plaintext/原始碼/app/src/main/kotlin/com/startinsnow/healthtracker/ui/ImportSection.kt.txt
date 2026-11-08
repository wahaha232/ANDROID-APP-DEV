package com.startinsnow.healthtracker.ui

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.startinsnow.healthtracker.cloud.CloudSync
import com.startinsnow.healthtracker.data.db.RecordType
import com.startinsnow.healthtracker.data.repository.HealthRecordRepository
import com.startinsnow.healthtracker.importer.CsvImporter
import com.startinsnow.healthtracker.importer.HcSample
import com.startinsnow.healthtracker.importer.HealthConnectImporter
import com.startinsnow.healthtracker.importer.HealthConnectMapping
import com.startinsnow.healthtracker.importer.PhotoBatchImporter
import com.startinsnow.healthtracker.mijia.MijiaAccessibilityService
import com.startinsnow.healthtracker.mijia.MijiaCaptureStore
import com.startinsnow.healthtracker.mijia.MijiaLauncher
import com.startinsnow.healthtracker.mijia.MijiaReading
import com.startinsnow.healthtracker.mijia.MijiaScreenParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_VISIBLE_HC_ROWS = 300
private val hcTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.TAIWAN)
private val mijiaTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.TAIWAN)

private fun fmtHcValue(s: HcSample): String = when (s.type) {
    RecordType.BLOOD_PRESSURE -> "${s.value.toInt()} mmHg"
    RecordType.BLOOD_GLUCOSE -> "${s.value.toInt()} ${s.type.unit}"
    else -> "${"%.1f".format(s.value)} ${s.type.unit}"
}

@Composable
fun ImportSection(records: HealthRecordRepository, cloud: CloudSync) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hc = remember { HealthConnectImporter(context) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var samples by remember { mutableStateOf<List<HcSample>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun scan() {
        scanning = true
        busy = true
        message = "掃描 Health Connect 中…"
        samples = emptyList()
        selected = emptySet()
        scope.launch {
            try {
                val found = hc.scan()
                samples = found
                selected = found.map { it.hcId }.toSet()
                message = if (found.isEmpty()) {
                    "Health Connect 裡沒有體重／體脂率資料。請確認米家或 Mi Fitness 已開啟「同步到 Health Connect」，或改用 CSV／手動輸入。"
                } else {
                    "找到 ${found.size} 筆（體重 ${found.count { it.type == RecordType.WEIGHT }}、體脂率 ${found.count { it.type == RecordType.BODY_FAT }}），預設全選；可逐筆取消。"
                }
            } catch (e: Exception) {
                message = "讀取失敗：${e.message}"
            } finally {
                scanning = false
                busy = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (granted.isEmpty()) message = "沒有授權，無法讀取 Health Connect" else scan()
    }

    var batchProgress by remember { mutableStateOf<String?>(null) }
    var batchDone by remember { mutableIntStateOf(0) }
    var batchTotal by remember { mutableIntStateOf(0) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        batchDone = 0
        batchTotal = uris.size
        scope.launch {
            try {
                val result = PhotoBatchImporter(context, records).run(uris) { done, total ->
                    batchDone = done
                    batchTotal = total
                    batchProgress = "處理中 $done / $total"
                }
                batchProgress = null
                message = result.summary() + "。"
                if (result.saved + result.attached > 0) {
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

    val filePhotoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        batchDone = 0
        batchTotal = uris.size
        scope.launch {
            try {
                val result = PhotoBatchImporter(context, records).run(uris) { done, total ->
                    batchDone = done
                    batchTotal = total
                    batchProgress = "處理中 $done / $total"
                }
                batchProgress = null
                message = result.summary() + "。"
                if (result.saved + result.attached > 0) {
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

        // ---- 血糖機照片 ----
        Text("血糖機照片（批次）", style = MaterialTheme.typography.titleMedium)
        Text("一次選多張血糖機照片：自動辨識數值，測量時間依照片拍攝時間，先存在手機，再接著上傳雲端。辨識可能有誤，請到「紀錄」點照片對照確認。", style = MaterialTheme.typography.bodySmall)
        Button(enabled = !busy, onClick = {
            message = null
            photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }) { Text(batchProgress ?: "批次匯入照片") }
        OutlinedButton(enabled = !busy, onClick = {
            message = null
            filePhotoPicker.launch(arrayOf("image/jpeg", "image/png"))
        }) { Text("從檔案選照片（批次）") }
        Text("同一時間已有紀錄但沒有照片時，會直接把照片補到那筆紀錄上（數值與情境不變）。", style = MaterialTheme.typography.bodySmall)
        if (batchTotal > 0 && busy) {
            LinearProgressIndicator(
                progress = { batchDone.toFloat() / batchTotal.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ---- 米家 S400 ----
        Text("小米體脂計 S400（米家）", style = MaterialTheme.typography.titleMedium)
        Text("HC Reader 可直接讀取米家 S400：體重＋體脂率（畫面讀取，不需 Health Connect）。", style = MaterialTheme.typography.bodySmall)
        MijiaCaptureStatus(records)

        // ---- Health Connect（HC Reader）----
        HorizontalDivider()
        Text("Health Connect（HC Reader）", style = MaterialTheme.typography.titleMedium)
        Text("讀取 Health Connect 的體重與體脂率；米家／Mi Fitness 有同步時，來源會顯示 com.xiaomi.smarthome。可逐筆勾選要匯入的紀錄。", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = {
                if (!hc.isAvailable()) {
                    message = "這支手機沒有可用的 Health Connect"
                } else {
                    scope.launch {
                        val missing = hc.missingPermissions()
                        if (missing.isEmpty()) scan() else permissionLauncher.launch(missing)
                    }
                }
            }) { Text("掃描 Health Connect") }
            OutlinedButton(enabled = !busy, onClick = {
                csvPicker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel", "*/*"))
            }) { Text("匯入 CSV") }
        }
        if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())

        if (samples.isNotEmpty()) {
            val display = samples.sortedByDescending { it.measuredAt }.take(MAX_VISIBLE_HC_ROWS)
            val byOrigin = display.groupBy { it.originPackage }.toSortedMap()
            val totalByOrigin = samples.groupBy { it.originPackage }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("來源／紀錄（已選 ${selected.size} / ${samples.size} 筆）", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { selected = samples.map { it.hcId }.toSet() }) { Text("全選") }
                TextButton(onClick = { selected = emptySet() }) { Text("全不選") }
            }
            if (samples.size > MAX_VISIBLE_HC_ROWS) {
                Text("清單只顯示最近 $MAX_VISIBLE_HC_ROWS 筆；「全選」會包含未顯示的紀錄。", style = MaterialTheme.typography.bodySmall)
            }
            byOrigin.forEach { (origin, rows) ->
                val allIds = totalByOrigin[origin].orEmpty().map { it.hcId }.toSet()
                val state = when {
                    allIds.isNotEmpty() && selected.containsAll(allIds) -> ToggleableState.On
                    allIds.none { it in selected } -> ToggleableState.Off
                    else -> ToggleableState.Indeterminate
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    TriStateCheckbox(
                        state = state,
                        onClick = { selected = if (state == ToggleableState.On) selected - allIds else selected + allIds },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(origin, style = MaterialTheme.typography.bodyMedium)
                        val w = totalByOrigin[origin].orEmpty().count { it.type == RecordType.WEIGHT }
                        val f = totalByOrigin[origin].orEmpty().count { it.type == RecordType.BODY_FAT }
                        Text("${totalByOrigin[origin].orEmpty().size} 筆（體重 $w、體脂率 $f）", style = MaterialTheme.typography.bodySmall)
                    }
                }
                rows.forEach { s ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            selected = if (s.hcId in selected) selected - s.hcId else selected + s.hcId
                        }.padding(start = 12.dp),
                    ) {
                        Checkbox(checked = s.hcId in selected, onCheckedChange = null)
                        Column(Modifier.weight(1f)) {
                            Text(fmtHcValue(s), style = MaterialTheme.typography.bodyMedium)
                            Text(hcTimeFormat.format(Date(s.measuredAt)), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Button(enabled = !busy && selected.isNotEmpty(), onClick = {
                busy = true
                scope.launch {
                    try {
                        val now = System.currentTimeMillis()
                        val mapped = samples.filter { it.hcId in selected }.mapNotNull { HealthConnectMapping.toRecord(it, now) }
                        val result = records.importRecords(mapped)
                        message = "新增 ${result.inserted} 筆，略過 ${result.skipped} 筆（已存在或數值不合理）"
                        samples = emptyList()
                        selected = emptySet()
                    } catch (e: Exception) {
                        message = "匯入失敗：${e.message}"
                    } finally {
                        busy = false
                    }
                }
            }) { Text("匯入選取的記錄（${selected.size}）") }
        }
        message?.let { Text(it) }
    }
}

private fun isMijiaServiceEnabled(context: android.content.Context): Boolean {
    val me = ComponentName(context, MijiaAccessibilityService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    return enabled.split(':').any { it.equals(me, ignoreCase = true) }
}

/** 米家畫面讀取（無障礙服務）的狀態、進度條、最近結果、除錯文字與開啟入口。 */
@Composable
private fun MijiaCaptureStatus(records: HealthRecordRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(isMijiaServiceEnabled(context)) }
    LifecycleResumeEffect(Unit) {
        enabled = isMijiaServiceEnabled(context) // 從系統設定回來時刷新
        onPauseOrDispose {}
    }
    val event by MijiaCaptureStore.event.collectAsState()
    var last by remember { mutableStateOf(MijiaCaptureStore.loadLast(context)) }
    var awaiting by remember { mutableStateOf(false) }
    var startSeq by remember { mutableStateOf(0L) }
    var message by remember { mutableStateOf<String?>(null) }
    var showRaw by remember { mutableStateOf(false) }

    // 服務讀到新結果時，同步更新最近一次摘要
    LaunchedEffect(event?.seq) {
        event?.reading?.let { last = MijiaCaptureStore.loadLast(context) }
    }

    // 等待米家畫面：讀到新結果、失敗或 25 秒逾時就停止進度條
    LaunchedEffect(awaiting, event?.seq) {
        if (!awaiting) return@LaunchedEffect
        val e = event
        if (e != null && e.seq > startSeq) {
            awaiting = false
            message = if (e.reading != null) {
                "已讀取：" + listOfNotNull(
                    e.reading?.weightKg?.let { "體重 ${"%.1f".format(it)} kg" },
                    e.reading?.bodyFatPercent?.let { "體脂率 ${"%.1f".format(it)} %" },
                ).joinToString("　") + "（新增 ${e.inserted} 筆，略過 ${e.skipped} 筆）"
            } else {
                e.message ?: "這次沒讀到，請停在量測結果頁再試一次。"
            }
        } else {
            delay(25_000)
            if (awaiting) {
                awaiting = false
                message = "25 秒內沒有讀到米家畫面。請確認米家停在「Xiaomi 體脂計 S400」的量測結果頁，再按「立即讀取畫面」。"
            }
        }
    }

    Text(
        when {
            !enabled -> "米家畫面讀取：未開啟。開啟後，打開米家的 S400 頁面就會自動擷取體重與體脂率（只對米家有效）。"
            MijiaCaptureStore.isServiceConnected() -> "米家畫面讀取：已開啟並連線。打開米家的 S400 頁面會自動讀取。"
            else -> "米家畫面讀取：已開啟，服務尚未連線（打開一次米家或重新開機後會連上）。"
        },
        style = MaterialTheme.typography.bodySmall,
    )
    Text("想補歷史紀錄：在米家 S400 頁面切到那一天的量測，畫面上出現哪一筆，就會讀哪一筆（已存過的不會重複）。", style = MaterialTheme.typography.bodySmall)

    last?.let { l ->
        val parts = listOfNotNull(
            l.weightKg?.let { "體重 ${"%.1f".format(it)} kg" },
            l.bodyFatPercent?.let { "體脂率 ${"%.1f".format(it)} %" },
        ).joinToString("　")
        Text("最近一次讀取：${mijiaTimeFormat.format(Date(l.readAt))}　量測 ${mijiaTimeFormat.format(Date(l.measuredAt))}", style = MaterialTheme.typography.bodySmall)
        if (parts.isNotEmpty()) {
            Text(parts + if (l.timeEstimated) "（時間為推估）" else "", style = MaterialTheme.typography.bodyMedium)
            Text("新增 ${l.inserted} 筆，已存在略過 ${l.skipped} 筆", style = MaterialTheme.typography.bodySmall)
        }
    }

    if (awaiting) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("正在等待米家的 S400 量測結果（最多 25 秒）…", style = MaterialTheme.typography.bodySmall)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !awaiting, onClick = {
            message = null
            startSeq = event?.seq ?: 0L
            awaiting = MijiaLauncher.openFloating(context)
            if (!awaiting) message = "找不到米家 App，請先安裝米家。"
        }) { Text("讀取米家 S400（浮動視窗）") }
        OutlinedButton(enabled = !awaiting, onClick = {
            val ok = MijiaCaptureStore.requestRefresh()
            message = if (ok) "已請服務重新讀取目前畫面…" else "無障礙服務尚未連線。請先開啟無障礙設定，或打開一次米家。"
        }) { Text("立即讀取畫面") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("開啟無障礙設定") }
        context.packageManager.getLaunchIntentForPackage(MijiaAccessibilityService.MIJIA_PACKAGE)?.let {
            OutlinedButton(onClick = { context.startActivity(it) }) { Text("開啟米家") }
        }
    }

    // 自動讀取失敗時的補救：手動把最近一次讀取寫入（仍會去重）
    last?.takeIf { it.weightKg != null || it.bodyFatPercent != null }?.let { l ->
        OutlinedButton(enabled = !awaiting, onClick = {
            scope.launch {
                val recs = MijiaScreenParser.toRecords(MijiaReading(l.measuredAt, l.weightKg, l.bodyFatPercent, l.timeEstimated))
                val r = records.importRecords(recs)
                message = "米家最近一次：新增 ${r.inserted} 筆，略過 ${r.skipped} 筆"
            }
        }) { Text("匯入最近一次米家讀取") }
    }

    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

    TextButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "收起畫面文字" else "顯示畫面文字（除錯用）") }
    if (showRaw) {
        val raw = event?.raw?.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: MijiaCaptureStore.loadRaw(context)
        Text(raw?.takeIf { it.isNotBlank() } ?: "（尚未記錄。請先開啟米家 S400 頁面）", style = MaterialTheme.typography.bodySmall)
    }
}
