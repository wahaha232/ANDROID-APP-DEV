package com.startinsnow.gpstracker.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.startinsnow.gpstracker.R
import com.startinsnow.gpstracker.core.analysis.Split
import com.startinsnow.gpstracker.core.analysis.SplitsAnalysis
import com.startinsnow.gpstracker.core.analysis.TimeBreakdown
import com.startinsnow.gpstracker.ui.components.formatDuration

private const val MAX_ROWS = 100

private fun pace(secPerKm: Double): String {
    val total = Math.round(secPerKm).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
private fun TwoColumnLine(label: String, value: String, sub: String? = null) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.titleSmall)
        }
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 詳情頁的「時間分析」（經過／活動／移動／暫停／停等）與「每公里分段」。資料不足的項目不顯示。 */
@Composable
fun TimeAndSplitsCard(breakdown: TimeBreakdown, distanceKm: Double, splits: List<Split>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.time_title), style = MaterialTheme.typography.titleMedium)
            fun ms(sec: Double) = formatDuration((sec * 1000).toLong())
            TwoColumnLine(stringResource(R.string.time_elapsed), ms(breakdown.elapsedSec))
            TwoColumnLine(stringResource(R.string.time_active), ms(breakdown.activeSec))
            if (breakdown.movingSec > 0) {
                TwoColumnLine(
                    stringResource(R.string.time_moving), ms(breakdown.movingSec),
                    stringResource(R.string.time_moving_speed, "%.1f".format(distanceKm / (breakdown.movingSec / 3600.0)))
                )
            }
            if (breakdown.pausedSec >= 1) TwoColumnLine(stringResource(R.string.time_paused), ms(breakdown.pausedSec))
            if (breakdown.movingSec > 0 && breakdown.stoppedSec >= 1) TwoColumnLine(stringResource(R.string.time_stopped), ms(breakdown.stoppedSec))

            if (splits.isNotEmpty()) {
                val extremes = SplitsAnalysis.fastestAndSlowest(splits)
                Text(stringResource(R.string.splits_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                splits.take(MAX_ROWS).forEach { s ->
                    val tag = when {
                        extremes != null && s.index == extremes.first.index && !s.partial -> " ⚡"
                        extremes != null && s.index == extremes.second.index && !s.partial -> " 🐢"
                        else -> ""
                    }
                    val label = if (s.partial) stringResource(R.string.splits_partial, "%.2f".format(s.distanceMeters / 1000.0))
                    else stringResource(R.string.splits_km, s.index)
                    TwoColumnLine(
                        label + tag,
                        pace(s.paceSecPerKm) + " /km",
                        "%.1f km/h".format(s.avgKmh)
                    )
                }
                Text(stringResource(R.string.splits_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
