package com.startinsnow.gpstracker.ui.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.startinsnow.gpstracker.R
import com.startinsnow.gpstracker.core.analysis.ProfileSeries

/**
 * 軌跡詳情的「剖面圖」：海拔或速度隨路程變化的折線與面積圖。
 * 沒有資料（例如沒有海拔）就不顯示對應的選項，兩種都沒有就整張卡片不出現。
 * 手指在圖上左右拖曳（或按住）可以讀該位置的數值；只攔截水平拖曳，不會擋住頁面上下捲動。
 */
@Composable
fun ProfileChartCard(altitude: DoubleArray?, speedKmh: DoubleArray?, modifier: Modifier = Modifier) {
    if (altitude == null && speedKmh == null) return
    var showSpeed by remember { mutableStateOf(altitude == null) }
    val raw = if (showSpeed) speedKmh else altitude
    val data = remember(raw) { raw?.let { ProfileSeries.bucketMean(it, MAX_CHART_POINTS) } } ?: return
    var marker by remember(showSpeed) { mutableStateOf<Float?>(null) }

    val low = data.min()
    val high = data.max()
    val context = LocalContext.current
    fun format(value: Double) = if (showSpeed) {
        context.getString(R.string.detail_profile_speed_value, value)
    } else {
        context.getString(R.string.detail_profile_altitude_value, value)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.detail_profile_title), style = MaterialTheme.typography.titleMedium)
            if (altitude != null && speedKmh != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !showSpeed,
                        onClick = { showSpeed = false },
                        label = { Text(stringResource(R.string.detail_profile_altitude)) }
                    )
                    FilterChip(
                        selected = showSpeed,
                        onClick = { showSpeed = true },
                        label = { Text(stringResource(R.string.detail_profile_speed)) }
                    )
                }
            }
            val readout = marker?.let { format(data[ProfileSeries.indexAt(it, data.size)]) }
                ?: context.getString(R.string.detail_profile_range, format(low), format(high))
            Text(readout, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)

            val line = MaterialTheme.colorScheme.primary
            val grid = MaterialTheme.colorScheme.outlineVariant
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .pointerInput(data) {
                        detectHorizontalDragGestures(
                            onDragStart = { marker = (it.x / size.width) },
                            onHorizontalDrag = { change, _ -> marker = (change.position.x / size.width).coerceIn(0f, 1f) },
                            onDragEnd = { marker = null },
                            onDragCancel = { marker = null }
                        )
                    }
                    .pointerInput(data) {
                        detectTapGestures(onPress = {
                            marker = (it.x / size.width).coerceIn(0f, 1f)
                            tryAwaitRelease()
                            marker = null
                        })
                    }
            ) {
                val w = size.width
                val h = size.height
                val top = h * 0.08f
                val usable = h * 0.84f
                fun x(i: Int) = if (data.size <= 1) 0f else w * i / (data.size - 1)
                fun y(v: Double) = top + usable * (1f - ProfileSeries.normalized(v, low, high))

                // 背景格線（上、中、下）
                for (k in 0..2) {
                    val gy = top + usable * k / 2f
                    drawLine(grid, Offset(0f, gy), Offset(w, gy), strokeWidth = 1.dp.toPx())
                }
                val path = Path().apply {
                    moveTo(x(0), y(data[0]))
                    for (i in 1 until data.size) lineTo(x(i), y(data[i]))
                }
                val area = Path().apply {
                    addPath(path)
                    lineTo(x(data.size - 1), h)
                    lineTo(x(0), h)
                    close()
                }
                drawPath(area, Brush.verticalGradient(listOf(line.copy(alpha = 0.35f), Color.Transparent)))
                drawPath(path, line, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))

                marker?.let { f ->
                    val i = ProfileSeries.indexAt(f, data.size)
                    drawLine(line.copy(alpha = 0.6f), Offset(x(i), 0f), Offset(x(i), h), strokeWidth = 1.dp.toPx())
                    drawCircle(line, radius = 5.dp.toPx(), center = Offset(x(i), y(data[i])))
                }
            }
            Text(
                stringResource(R.string.detail_profile_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private const val MAX_CHART_POINTS = 240
