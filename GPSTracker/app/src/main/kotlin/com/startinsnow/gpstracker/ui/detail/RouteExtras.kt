package com.startinsnow.gpstracker.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.startinsnow.gpstracker.R
import com.startinsnow.gpstracker.core.analysis.ColorMode
import com.startinsnow.gpstracker.core.analysis.HighlightKind
import com.startinsnow.gpstracker.core.analysis.RouteHighlight
import com.startinsnow.gpstracker.ui.components.formatDuration

/**
 * 詳情頁的「路線顏色（單色／速度／海拔）」、「活動亮點」與「分享圖片」。
 * 只列出資料真的支援的選項；沒有速度或海拔資料就不顯示對應的按鈕或亮點。
 */
@Composable
fun RouteExtras(
    colorMode: ColorMode,
    speedAvailable: Boolean,
    altitudeAvailable: Boolean,
    legendText: String?,
    highlights: List<RouteHighlight>,
    onColorMode: (ColorMode) -> Unit,
    onHighlight: (RouteHighlight) -> Unit,
    onShare: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (speedAvailable || altitudeAvailable) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.route_color_title), style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = colorMode == ColorMode.NONE, onClick = { onColorMode(ColorMode.NONE) },
                            label = { Text(stringResource(R.string.route_color_none)) })
                        if (speedAvailable) FilterChip(selected = colorMode == ColorMode.SPEED, onClick = { onColorMode(ColorMode.SPEED) },
                            label = { Text(stringResource(R.string.route_color_speed)) })
                        if (altitudeAvailable) FilterChip(selected = colorMode == ColorMode.ALTITUDE, onClick = { onColorMode(ColorMode.ALTITUDE) },
                            label = { Text(stringResource(R.string.route_color_altitude)) })
                    }
                    if (legendText != null) {
                        Text(legendText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (highlights.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.hl_title), style = MaterialTheme.typography.titleMedium)
                    highlights.forEach { h ->
                        val label = when (h.kind) {
                            HighlightKind.MAX_ALTITUDE -> stringResource(R.string.hl_max_altitude)
                            HighlightKind.BIGGEST_CLIMB -> stringResource(R.string.hl_climb)
                            HighlightKind.MAX_SPEED -> stringResource(R.string.hl_max_speed)
                        }
                        val value = when (h.kind) {
                            HighlightKind.MAX_ALTITUDE -> "%.0f m".format(h.value)
                            HighlightKind.BIGGEST_CLIMB -> "+%.0f m".format(h.value)
                            HighlightKind.MAX_SPEED -> "%.1f km/h".format(h.value)
                        }
                        TextButton(onClick = { onHighlight(h) }, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column {
                                    Text(label, style = MaterialTheme.typography.bodyLarge)
                                    if (h.elapsedMs > 0) {
                                        Text(
                                            stringResource(R.string.hl_time_fmt, formatDuration(h.elapsedMs)),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Text(value, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                    Text(stringResource(R.string.hl_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.share_card_button)) }
    }
}
