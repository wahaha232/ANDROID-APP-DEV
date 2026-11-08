package com.startinsnow.healthtracker.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.startinsnow.healthtracker.BuildConfig
import com.startinsnow.healthtracker.data.prefs.UserSettingsRepository
import com.startinsnow.healthtracker.data.repository.HealthRecordRepository
import com.startinsnow.healthtracker.data.repository.ReportBuilder
import com.startinsnow.healthtracker.ocr.PhotoStore
import com.startinsnow.healthtracker.report.PdfReport
import com.startinsnow.healthtracker.report.SampleReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 產生就醫摘要 PDF 並用系統分享（LINE、Email、列印…）。檔案只放快取，產生新的之前先清掉舊的。 */
@Composable
fun ReportSection(records: HealthRecordRepository, settings: UserSettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var days by rememberSaveable { mutableIntStateOf(30) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("就醫摘要", style = MaterialTheme.typography.titleLarge)
        Text(
            "把一段期間的血糖統計、各情境平均、趨勢圖與明細整理成一份 PDF，回診時可以直接給醫師看。內容只整理您自己記錄的數值，不含任何判斷。",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7, 30, 90).forEach { d ->
                FilterChip(selected = days == d, onClick = { days = d }, label = { Text("近 $d 天") })
            }
        }
        suspend fun generate(all: List<com.startinsnow.healthtracker.data.db.HealthRecord>, name: String, low: Double?, high: Double?) {
            run {
                try {
                    val now = System.currentTimeMillis()
                    val data = ReportBuilder.build(all, name, low, high, days, now)
                    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() } // 舊的報告含健康資料，不留在快取
                    val stamp = DateTimeFormatter.ofPattern("yyyyMMdd").format(java.time.Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()))
                    val file = File(dir, "glucose_report_${stamp}_${days}d.pdf")
                    withContext(Dispatchers.IO) { file.outputStream().use { PdfReport.write(data, now, ZoneId.systemDefault(), it) } }
                    val uri = FileProvider.getUriForFile(context, PhotoStore.authority(context), file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "分享血糖記錄報告"))
                    message = "已產生報告：血糖 ${data.glucoseTotal} 筆（近 $days 天）"
                } catch (e: Exception) {
                    message = "產生報告失敗：${e.message}"
                } finally {
                    busy = false
                }
            }
        }

        Button(enabled = !busy, onClick = {
            busy = true
            message = null
            scope.launch {
                val user = settings.settings.first()
                generate(records.observeAll().first(), user.displayName, user.glucoseLow, user.glucoseHigh)
            }
        }) { Text(if (busy) "產生中…" else "產生 PDF 並分享") }
        if (BuildConfig.DEBUG) {
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                message = null
                scope.launch { generate(SampleReport.records(System.currentTimeMillis()), "範例", 70.0, 140.0) }
            }) { Text("預覽範例報告（假資料，僅除錯版）") }
        }
        message?.let { Text(it) }
    }
}
