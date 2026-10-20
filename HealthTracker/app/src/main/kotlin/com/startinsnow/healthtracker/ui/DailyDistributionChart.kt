package com.startinsnow.healthtracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.startinsnow.healthtracker.data.db.HealthRecord
import com.startinsnow.healthtracker.data.repository.CalendarMath
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dotDay = DateTimeFormatter.ofPattern("MM/dd")
private val dotTime = DateTimeFormatter.ofPattern("MM/dd HH:mm")

/**
 * 時段分布圖：橫軸一天 24 小時、每天一列，每筆紀錄一個圓點（同一種顏色，不依數值好壞上色）。
 * 用來看自己習慣在一天的哪些時段測量。概念參考 JetLagged 的時間軸（Apache-2.0），位置＝時間比例。
 */
@Composable
fun DailyDistributionChart(records: List<HealthRecord>, rows: Int, zone: ZoneId, today: LocalDate) {
    val dots = remember(records, rows, today) { CalendarMath.dayDots(records, rows, today, zone) }
    var sel by remember(dots) { mutableStateOf<Int?>(null) }
    val dark = isSystemInDarkTheme()
    val dotColor = if (dark) Color(0xFF3987E5) else Color(0xFF2A78D6)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val ring = MaterialTheme.colorScheme.surfaceContainerHighest
    val measurer = rememberTextMeasurer()
    val label = TextStyle(fontSize = 10.sp, color = textColor)
    val rowHeight = 18.dp

    Column {
        Canvas(
            Modifier.fillMaxWidth().height(rowHeight * rows + 24.dp)
                .semantics { contentDescription = "時段分布圖，最近 $rows 天共 ${dots.size} 筆；點圓點可看數值與時間" }
                .pointerInput(dots) {
                    detectTapGestures { o ->
                        val left = 44.dp.toPx()
                        val w = size.width - left - 10.dp.toPx()
                        val rh = rowHeight.toPx()
                        val hit = dots.withIndex().minByOrNull { (_, d) ->
                            val dx = left + d.fraction * w - o.x
                            val dy = 8.dp.toPx() + (rows - 1 - d.row) * rh + rh / 2 - o.y
                            dx * dx + dy * dy
                        }
                        sel = hit?.let { (i, d) ->
                            val dx = left + d.fraction * w - o.x
                            val dy = 8.dp.toPx() + (rows - 1 - d.row) * rh + rh / 2 - o.y
                            if (dx * dx + dy * dy <= 28.dp.toPx() * 28.dp.toPx()) i else null
                        }
                    }
                },
        ) {
            val left = 44.dp.toPx()
            val w = size.width - left - 10.dp.toPx()
            val top = 8.dp.toPx()
            val rh = rowHeight.toPx()
            val bodyH = rh * rows
            listOf(0, 6, 12, 18, 24).forEach { h ->
                val x = left + w * h / 24f
                drawLine(grid, Offset(x, top), Offset(x, top + bodyH), strokeWidth = 1.dp.toPx())
                val m = measurer.measure("${h}h", label)
                drawText(m, topLeft = Offset((x - m.size.width / 2f).coerceIn(left - 6.dp.toPx(), size.width - m.size.width), top + bodyH + 4.dp.toPx()))
            }
            for (row in 0 until rows) {
                val y = top + (rows - 1 - row) * rh + rh / 2
                if (row % 2 == 0 || rows <= 8) {
                    val m = measurer.measure(dotDay.format(today.minusDays(row.toLong())), label)
                    drawText(m, topLeft = Offset(left - 6.dp.toPx() - m.size.width, y - m.size.height / 2f))
                }
            }
            dots.forEachIndexed { i, d ->
                val c = Offset(left + d.fraction * w, top + (rows - 1 - d.row) * rh + rh / 2)
                if (i == sel) {
                    drawCircle(ring, radius = 9.dp.toPx(), center = c)
                    drawCircle(dotColor, radius = 7.dp.toPx(), center = c)
                } else {
                    drawCircle(dotColor, radius = 4.5.dp.toPx(), center = c)
                }
            }
        }
        val picked = sel?.let { dots.getOrNull(it) }?.record
        Text(
            modifier = Modifier.padding(top = 6.dp),
            text = if (picked != null) "${picked.value.toInt()} mg/dL　" + dotTime.format(Instant.ofEpochMilli(picked.measuredAt).atZone(zone))
            else "點圓點可看數值與時間；每個點代表一次測量。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
