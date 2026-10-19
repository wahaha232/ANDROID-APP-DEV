package com.startinsnow.healthtracker.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.TAIWAN)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecordsScreen(records: HealthRecordRepository, settings: UserSettingsRepository) {
    val list by records.observeAll().collectAsState(initial = emptyList())
    val user by settings.settings.collectAsState(initial = UserSettings())
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf<RecordType?>(null) } // null = 全部
    var pendingDelete by remember { mutableStateOf<HealthRecord?>(null) }
    val shown = if (filter == null) list else list.filter { it.type == filter }

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
            if (list.isEmpty()) {
                Text(
                    "尚無紀錄，點右下角「＋」新增第一筆。",
                    Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else if (shown.isEmpty()) {
                Text("這個類型還沒有紀錄。", Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.id }) { r ->
                        RecordCard(r, user) { pendingDelete = r }
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

    if (showAdd) {
        AddRecordDialog(
            records = records,
            onDismiss = { showAdd = false },
            onSaved = { showAdd = false },
        )
    }
}

private fun formatMain(r: HealthRecord): String = when (r.type) {
    RecordType.BLOOD_PRESSURE -> "${r.value.toInt()}/${r.value2?.toInt()} ${r.type.unit}"
    RecordType.BLOOD_GLUCOSE -> "${r.value.toInt()} ${r.type.unit}"
    else -> "${"%.1f".format(r.value)} ${r.type.unit}"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordCard(r: HealthRecord, user: UserSettings, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("${r.type.label}　${formatMain(r)}", style = MaterialTheme.typography.titleMedium)
                if (r.type == RecordType.WEIGHT) {
                    user.bmi(r.value)?.let { Text("BMI ${"%.1f".format(it)}") }
                }
                Text(timeFormat.format(Date(r.measuredAt)), style = MaterialTheme.typography.bodySmall)
                if (r.note.isNotEmpty()) Text(r.note, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "刪除") }
        }
    }
}
