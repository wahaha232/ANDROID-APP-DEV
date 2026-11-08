package com.startinsnow.healthtracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.startinsnow.healthtracker.data.db.HealthRecord
import com.startinsnow.healthtracker.data.repository.CalendarMath
import com.startinsnow.healthtracker.ui.theme.BrandTeal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dayTime = DateTimeFormatter.ofPattern("HH:mm")

/**
 * 日曆熱度圖：每天一格，顏色深淺只代表「那天測了幾次」，不看數值，所以沒有好壞的意思。
 * 點一天會在下方列出當天的紀錄。寫法參考 heatmap-compose（MIT）的格狀熱度圖概念，這裡用 Compose 格子重新實作。
 */
@Composable
fun CalendarHeatmap(records: List<HealthRecord>, zone: ZoneId, today: LocalDate) {
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    var pickedText by rememberSaveable { mutableStateOf<String?>(null) }
    val month = YearMonth.parse(monthText)
    val picked = pickedText?.let { LocalDate.parse(it) }
    val counts = remember(records, zone) { CalendarMath.countsByDay(records, zone) }
    val grid = remember(month) { CalendarMath.monthGrid(month) }
    val levelColors = listOf(
        MaterialTheme.colorScheme.surfaceContainerHighest,
        BrandTeal.copy(alpha = 0.35f),
        BrandTeal.copy(alpha = 0.65f),
        BrandTeal,
    )
    val outline = MaterialTheme.colorScheme.outlineVariant

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = { monthText = month.minusMonths(1).toString(); pickedText = null }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上個月")
            }
            Text(
                "${month.year} 年 ${month.monthValue} 月", style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            IconButton(enabled = month < YearMonth.from(today), onClick = { monthText = month.plusMonths(1).toString(); pickedText = null }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下個月")
            }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("日", "一", "二", "三", "四", "五", "六").forEach {
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        grid.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 0 until 7) {
                    val date = week.getOrNull(i)
                    if (date == null) {
                        Box(Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        val n = counts[date] ?: 0
                        val level = CalendarMath.level(n)
                        val isPicked = date == picked
                        Box(
                            Modifier.weight(1f).aspectRatio(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(levelColors[level])
                                .border(if (isPicked) 2.dp else 0.5.dp, if (isPicked) MaterialTheme.colorScheme.primary else outline, RoundedCornerShape(6.dp))
                                .clickable(enabled = date <= today) { pickedText = if (isPicked) null else date.toString() }
                                .semantics { contentDescription = "${date.monthValue} 月 ${date.dayOfMonth} 日，$n 筆" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${date.dayOfMonth}", style = MaterialTheme.typography.labelSmall,
                                color = if (level >= 2) Color.White else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("少", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            levelColors.forEach { Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(it)) }
            Text("多（顏色只代表測量次數）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        picked?.let { d ->
            val day = records.filter { Instant.ofEpochMilli(it.measuredAt).atZone(zone).toLocalDate() == d }.sortedBy { it.measuredAt }
            Text(
                if (day.isEmpty()) "${d.monthValue}/${d.dayOfMonth}：沒有紀錄"
                else "${d.monthValue}/${d.dayOfMonth}：" + day.joinToString("　") { "${it.value.toInt()}（${dayTime.format(Instant.ofEpochMilli(it.measuredAt).atZone(zone))}）" },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
