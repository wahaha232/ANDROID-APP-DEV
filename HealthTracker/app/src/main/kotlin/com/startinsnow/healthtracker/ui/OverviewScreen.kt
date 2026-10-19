package com.startinsnow.healthtracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.startinsnow.healthtracker.cloud.CloudSync
import com.startinsnow.healthtracker.data.db.HealthRecord
import com.startinsnow.healthtracker.data.db.RecordType
import com.startinsnow.healthtracker.data.repository.HealthRecordRepository
import com.startinsnow.healthtracker.data.repository.OverviewMath
import com.startinsnow.healthtracker.ui.theme.AppCard
import com.startinsnow.healthtracker.ui.theme.BrandBlue
import com.startinsnow.healthtracker.ui.theme.BrandTeal
import com.startinsnow.healthtracker.ui.theme.recordAccent
import com.startinsnow.healthtracker.ui.theme.recordIcon
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
private fun GlucoseHero(latest: HealthRecord?, now: Long, zone: ZoneId, avg7: Double?) {
    val brush = Brush.linearGradient(listOf(BrandBlue, BrandTeal))
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(brush).padding(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(Color.White), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Icon(Icons.Filled.WaterDrop, contentDescription = null, tint = Color(0xFFD6303E), modifier = Modifier.size(18.dp))
                }
                Text("最新血糖", style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.9f))
            }
            if (latest == null) {
                Text("還沒有血糖紀錄", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text("按下面的「記錄血糖」新增第一筆，可用拍照辨識。", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f))
            } else {
                Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${latest.value.toInt()}", style = MaterialTheme.typography.displayLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = Color.White)
                    Text(RecordType.BLOOD_GLUCOSE.unit, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.9f), modifier = Modifier.padding(bottom = 10.dp))
                }
                Text(
                    dateTime.format(Instant.ofEpochMilli(latest.measuredAt).atZone(zone)) + "（${OverviewMath.relativeAge(latest.measuredAt, now)}）",
                    style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f),
                )
                avg7?.let {
                    Text("近 7 天平均 ${"%.0f".format(it)} mg/dL", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
                }
            }
        }
    }
}

private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val monthDay = DateTimeFormatter.ofPattern("MM/dd")

/** 總覽：最新血糖、快速新增、近 7／30 天趨勢、其他類型最新值、最近紀錄、待同步提示。沒有資料就顯示空狀態，不補假資料。 */
@Composable
fun OverviewScreen(records: HealthRecordRepository, cloud: CloudSync, onSeeAll: () -> Unit) {
    val list by records.observeAll().collectAsState(initial = emptyList())
    val pending by cloud.pendingCount.collectAsState(initial = 0)
    var days by rememberSaveable { mutableIntStateOf(7) }
    var addType by remember { mutableStateOf<RecordType?>(null) }
    val zone = remember { ZoneId.systemDefault() }
    val now = System.currentTimeMillis()
    val latestGlucose = OverviewMath.latest(list, RecordType.BLOOD_GLUCOSE)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlucoseHero(latestGlucose, now, zone, OverviewMath.stats(OverviewMath.window(list, RecordType.BLOOD_GLUCOSE, 7, now))?.avg)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { addType = RecordType.BLOOD_GLUCOSE }, modifier = Modifier.weight(1f)) { Text("記錄血糖") }
            FilledTonalButton(onClick = { addType = RecordType.WEIGHT }, modifier = Modifier.weight(1f)) { Text("記錄其他") }
        }

        TrendCard(list, days, now, zone) { days = it }

        val others = listOf(RecordType.WEIGHT, RecordType.BODY_FAT, RecordType.BLOOD_PRESSURE)
            .mapNotNull { t -> OverviewMath.latest(list, t) }
        if (others.isNotEmpty()) {
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("其他最新數值", style = MaterialTheme.typography.titleSmall)
                    others.forEach { r ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(recordIcon(r.type), contentDescription = null, tint = recordAccent(r.type), modifier = Modifier.size(20.dp))
                            Text("${r.type.label}　${formatMain(r)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(OverviewMath.relativeAge(r.measuredAt, now), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        if (list.isNotEmpty()) {
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("最近紀錄", style = MaterialTheme.typography.titleSmall)
                    list.take(3).forEach { r ->
                        Text(
                            "${r.type.label}　${formatMain(r)}　" + dateTime.format(Instant.ofEpochMilli(r.measuredAt).atZone(zone)),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    TextButton(onClick = onSeeAll) { Text("查看全部紀錄") }
                }
            }
        }

        if (pending > 0) {
            Text("有 $pending 筆紀錄等待同步到雲端（到設定頁按「立即同步」，或等每晚 20:00 自動上傳）。", style = MaterialTheme.typography.bodySmall)
        }
    }

    addType?.let { t ->
        AddRecordDialog(records = records, initialType = t, onDismiss = { addType = null }, onSaved = { addType = null })
    }
}

@Composable
private fun TrendCard(list: List<HealthRecord>, days: Int, now: Long, zone: ZoneId, onDays: (Int) -> Unit) {
    val window = OverviewMath.window(list, RecordType.BLOOD_GLUCOSE, days, now)
    val stats = OverviewMath.stats(window)
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("血糖趨勢（mg/dL）", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = days == 7, onClick = { onDays(7) }, label = { Text("近 7 天") })
                FilterChip(selected = days == 30, onClick = { onDays(30) }, label = { Text("近 30 天") })
            }
            if (window.size < 2) {
                Text(
                    if (window.isEmpty()) "近 $days 天沒有血糖紀錄" else "資料不足（近 $days 天只有 1 筆，至少要 2 筆才畫趨勢）",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                TrendChart(window, zone)
            }
            stats?.let {
                Text(
                    "近 $days 天：${it.count} 筆　平均 ${"%.0f".format(it.avg)}　最低 ${"%.0f".format(it.min)}　最高 ${"%.0f".format(it.max)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** 折線圖（2dp 線、含底色環的圓點、髮絲格線；文字用文字色，線色藍色，亮／暗各自選色）。 */
@Composable
private fun TrendChart(points: List<HealthRecord>, zone: ZoneId) {
    val dark = isSystemInDarkTheme()
    val lineColor = if (dark) Color(0xFF3987E5) else Color(0xFF2A78D6)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val ringColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val measurer = rememberTextMeasurer()
    val label = TextStyle(fontSize = 10.sp, color = textColor)
    val scale = OverviewMath.niceScale(points.minOf { it.value }, points.maxOf { it.value })
    val description = "血糖趨勢圖，${points.size} 筆，最新 ${points.last().value.toInt()} mg/dL"

    Canvas(Modifier.fillMaxWidth().height(180.dp).semantics { contentDescription = description }) {
        val left = 34.dp.toPx()
        val right = 8.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = 20.dp.toPx()
        val w = size.width - left - right
        val h = size.height - top - bottom
        val t0 = points.first().measuredAt
        val t1 = points.last().measuredAt
        fun x(t: Long) = left + if (t1 == t0) w / 2 else (t - t0).toFloat() / (t1 - t0) * w
        fun y(v: Double) = top + h - ((v - scale.min) / (scale.max - scale.min)).toFloat() * h

        scale.ticks.forEach { tick ->
            val ty = y(tick)
            drawLine(gridColor, Offset(left, ty), Offset(left + w, ty), strokeWidth = 1.dp.toPx())
            val text = "%.0f".format(tick)
            val m = measurer.measure(text, label)
            drawText(m, topLeft = Offset(left - 6.dp.toPx() - m.size.width, ty - m.size.height / 2f))
        }
        listOf(t0, t1).distinct().forEachIndexed { i, t ->
            val text = monthDay.format(Instant.ofEpochMilli(t).atZone(zone))
            val m = measurer.measure(text, label)
            val tx = if (i == 0) left else left + w - m.size.width
            drawText(m, topLeft = Offset(tx, size.height - m.size.height))
        }
        for (i in 1 until points.size) {
            drawLine(
                lineColor, Offset(x(points[i - 1].measuredAt), y(points[i - 1].value)), Offset(x(points[i].measuredAt), y(points[i].value)),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
            )
        }
        points.forEach { p ->
            drawCircle(ringColor, radius = 6.dp.toPx(), center = Offset(x(p.measuredAt), y(p.value)))
            drawCircle(lineColor, radius = 4.dp.toPx(), center = Offset(x(p.measuredAt), y(p.value)))
        }
        val last = points.last()
        val lastText = measurer.measure("${last.value.toInt()}", TextStyle(fontSize = 11.sp, color = textColor))
        drawText(lastText, topLeft = Offset((x(last.measuredAt) - lastText.size.width / 2f).coerceIn(left, left + w - lastText.size.width), y(last.value) - lastText.size.height - 8.dp.toPx()))
    }
}
