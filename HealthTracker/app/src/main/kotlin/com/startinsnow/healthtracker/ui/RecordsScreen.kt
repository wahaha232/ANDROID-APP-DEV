package com.startinsnow.healthtracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.startinsnow.healthtracker.data.db.HealthRecord
import com.startinsnow.healthtracker.data.db.RecordType
import com.startinsnow.healthtracker.data.prefs.UserSettings
import com.startinsnow.healthtracker.data.prefs.UserSettingsRepository
import com.startinsnow.healthtracker.data.repository.HealthRecordRepository
import com.startinsnow.healthtracker.ui.theme.AppCard
import com.startinsnow.healthtracker.ui.theme.recordAccent
import com.startinsnow.healthtracker.ui.theme.recordIcon
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.TAIWAN)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordsScreen(records: HealthRecordRepository, settings: UserSettingsRepository) {
    val loaded by records.observeAll().collectAsState(initial = null) // null = 還沒載入，先不顯示空狀態
    val list = loaded.orEmpty()
    val user by settings.settings.collectAsState(initial = UserSettings())
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf<RecordType?>(null) } // null = 全部
    var pendingDelete by remember { mutableStateOf<HealthRecord?>(null) }
    var editing by remember { mutableStateOf<HealthRecord?>(null) }
    var viewingPhoto by remember { mutableStateOf<String?>(null) }
    // 「全部」一定要包含所有類型（體重／體脂率／血糖／血壓），不可只列部分；並固定依時間新到舊。
    val shown = recordsForTab(list, filter)
    val rows = buildList {
        var lastDay: String? = null
        shown.forEach { r ->
            val day = dayLabel(r.measuredAt)
            if (day != lastDay) {
                add(RecordListRow.Day(day))
                lastDay = day
            }
            add(RecordListRow.Item(r))
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = "新增紀錄")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (list.isNotEmpty()) {
                FlowRow(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("全部") })
                    RecordType.entries.forEach { t ->
                        FilterChip(selected = filter == t, onClick = { filter = t }, label = { Text(t.label) })
                    }
                }
            }
            if (loaded == null) {
                // 載入中：留白
            } else if (list.isEmpty()) {
                EmptyState(Icons.Filled.WaterDrop, "還沒有紀錄", "新增第一筆血糖、體重或血壓，之後就能在這裡查看與編輯。", "新增紀錄") { showAdd = true }
            } else if (shown.isEmpty()) {
                EmptyState(recordIcon(filter!!), "這個類型還沒有紀錄", "切換上方篩選，或新增一筆${filter!!.label}。")
            } else {
                if (filter == null) {
                    Text(
                        summaryText(list),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        rows,
                        key = { row ->
                            when (row) {
                                is RecordListRow.Day -> "day-${row.label}"
                                is RecordListRow.Item -> row.record.id
                            }
                        },
                    ) { row ->
                        when (row) {
                            is RecordListRow.Day -> Text(
                                row.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                            )
                            is RecordListRow.Item -> {
                                val r = row.record
                                RecordCard(r, user, onEdit = { editing = r }, onPhoto = { viewingPhoto = r.photoPath }, onDelete = { pendingDelete = r })
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("刪除這筆紀錄？") },
            text = {
                Text(
                    "${r.type.label}　${formatMain(r)}\n${timeFormat.format(Date(r.measuredAt))}\n\n" +
                        "刪除後無法復原" + if (r.photoPath != null) "，原始照片也會一併刪除。" else "。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch { records.delete(r.id) }
                }) { Text("刪除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    editing?.let { r ->
        AddRecordDialog(
            records = records,
            existing = r,
            onDismiss = { editing = null },
            onSaved = { editing = null },
        )
    }

    viewingPhoto?.let { path -> PhotoViewDialog(path) { viewingPhoto = null } }

    if (showAdd) {
        AddRecordDialog(
            records = records,
            onDismiss = { showAdd = false },
            onSaved = { showAdd = false },
        )
    }
}

/**
 * 「全部」分頁必須包含每一種類型（體重、體脂率、血糖、血壓），指定類型時只留該類型。
 * 一律依量測時間新到舊，避免清單只列出部分資料。
 */
internal fun recordsForTab(all: List<HealthRecord>, filter: RecordType?): List<HealthRecord> =
    (if (filter == null) all else all.filter { it.type == filter }).sortedByDescending { it.measuredAt }

internal fun typeCounts(all: List<HealthRecord>): Map<RecordType, Int> =
    all.groupingBy { it.type }.eachCount()

internal fun summaryText(all: List<HealthRecord>): String {
    val counts = typeCounts(all)
    val parts = RecordType.entries.mapNotNull { t -> counts[t]?.takeIf { it > 0 }?.let { "${t.label} $it" } }
    return "全部 ${all.size} 筆：" + parts.joinToString("、")
}

private sealed interface RecordListRow {
    data class Day(val label: String) : RecordListRow
    data class Item(val record: HealthRecord) : RecordListRow
}

internal fun formatMain(r: HealthRecord): String = when (r.type) {
    RecordType.BLOOD_PRESSURE -> "${r.value.toInt()}/${r.value2?.toInt()} ${r.type.unit}"
    RecordType.BLOOD_GLUCOSE -> "${r.value.toInt()} ${r.type.unit}"
    else -> "${"%.1f".format(r.value)} ${r.type.unit}"
}

private val dayFormat = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd（E）", Locale.TAIWAN)
private val clockFormat = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

private fun dayLabel(ms: Long): String = dayFormat.format(java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()))

@Composable
private fun RecordCard(r: HealthRecord, user: UserSettings, onEdit: () -> Unit, onPhoto: () -> Unit, onDelete: () -> Unit) {
    val accent = recordAccent(r.type)
    AppCard(Modifier.fillMaxWidth(), onClick = onEdit) {
        Row(
            Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(accent.copy(alpha = 0.14f)),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) { Icon(recordIcon(r.type), contentDescription = r.type.label, tint = accent, modifier = Modifier.size(22.dp)) }
            Column(Modifier.weight(1f)) {
                Text(formatMain(r), style = MaterialTheme.typography.titleMedium)
                val sub = buildString {
                    append(r.type.label)
                    r.context?.let { append("・").append(it.label) }
                    append("　").append(clockFormat.format(java.time.Instant.ofEpochMilli(r.measuredAt).atZone(java.time.ZoneId.systemDefault())))
                    if (r.type == RecordType.WEIGHT) user.bmi(r.value)?.let { append("　BMI ${"%.1f".format(it)}") }
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // 依衛福部／WHO 標準判定（資料不足就不顯示）
                assessmentFor(r, user)?.let { a ->
                    Text("${a.label}（標準判定）", style = MaterialTheme.typography.bodySmall, color = assessmentColor(a.severity))
                }
                if (r.note.isNotEmpty()) Text(r.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (r.photoPath != null && File(r.photoPath).isFile) {
                IconButton(onClick = onPhoto) { Icon(Icons.Filled.PhotoCamera, contentDescription = "查看照片") }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "刪除") }
        }
    }
}
