package com.startinsnow.healthtracker.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.startinsnow.healthtracker.backup.BackupCodec
import com.startinsnow.healthtracker.backup.BackupException
import com.startinsnow.healthtracker.data.repository.HealthRecordRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BackupSection(records: HealthRecordRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                val all = records.allForBackup()
                val bytes = withContext(Dispatchers.Default) { BackupCodec.encode(all, password) }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("無法寫入檔案")
                }
                message = "已備份 ${all.size} 筆" + if (password.isEmpty()) "（未加密，請妥善保管檔案）" else "（已用密碼加密，忘記密碼就無法還原）"
            } catch (e: Exception) {
                message = "備份失敗：${e.message}"
            } finally {
                busy = false
            }
        }
    }

    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                    ?: throw BackupException("無法讀取檔案")
                val decoded = withContext(Dispatchers.Default) { BackupCodec.decode(bytes, password) }
                val incoming = decoded.mapNotNull(BackupCodec::toHealthRecord)
                val result = records.restore(incoming)
                message = "還原完成：新增 ${result.inserted} 筆、更新 ${result.updated} 筆、略過 ${result.skipped} 筆（已有或較新）" +
                    if (decoded.size > incoming.size) "；${decoded.size - incoming.size} 筆來自較新版本，已略過" else ""
            } catch (e: BackupException) {
                message = e.message
            } catch (e: Exception) {
                message = "還原失敗：${e.message}"
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("備份與還原", style = MaterialTheme.typography.titleLarge)
        Text(
            "把全部紀錄存成一個檔案（不含照片）；換手機或誤刪時可還原。密碼選填：有填就加密，還原時要輸入同一組密碼。",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            password, { password = it }, label = { Text("備份密碼（選填）") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = {
                val stamp = SimpleDateFormat("yyyyMMdd", Locale.TAIWAN).format(Date())
                createDoc.launch("血糖記錄備份_$stamp.json")
            }) { Text("備份") }
            OutlinedButton(enabled = !busy, onClick = {
                openDoc.launch(arrayOf("application/json", "text/plain", "*/*"))
            }) { Text("還原") }
        }
        message?.let { Text(it) }
    }
}
