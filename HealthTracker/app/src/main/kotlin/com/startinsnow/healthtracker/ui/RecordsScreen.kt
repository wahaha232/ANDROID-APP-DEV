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

@Composable
fun RecordsScreen(records: HealthRecordRepository, settings: UserSettingsRepository) {
    val list by records.observeAll().collectAsState(initial = emptyList())
    val user by settings.settings.collectAsState(initial = UserSettings())
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = "新增紀錄")
            }
        },
    ) { padding ->
        if (list.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text("尚無紀錄，點右下角「＋」新增第一筆。", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.id }) { r ->
                    RecordCard(r, user) { scope.launch { records.delete(r.id) } }
                }
            }
        }
    }

    if (showAdd) {
        AddRecordDialog(
            records = records,
            onDismiss = { showAdd = false },
            onSaved = { showAdd = false },
        )
    }
}

@Composable
private fun RecordCard(r: HealthRecord, user: UserSettings, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                val main = if (r.type == RecordType.BLOOD_PRESSURE) {
                    "${r.value.toInt()}/${r.value2?.toInt()} ${r.type.unit}"
                } else if (r.type == RecordType.BLOOD_GLUCOSE) {
                    "${r.value.toInt()} ${r.type.unit}"
                } else {
                    "${"%.1f".format(r.value)} ${r.type.unit}"
                }
                Text("${r.type.label}　$main", style = MaterialTheme.typography.titleMedium)
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
