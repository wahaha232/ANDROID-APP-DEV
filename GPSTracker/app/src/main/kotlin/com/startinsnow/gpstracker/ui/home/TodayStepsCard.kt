package com.startinsnow.gpstracker.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.startinsnow.gpstracker.R
import com.startinsnow.gpstracker.data.prefs.StepSource
import com.startinsnow.gpstracker.sync.StepSourceTag

/** 今日步數（來自 Zepp Life／Google Fit，經 Health Connect）。首頁一回到前景就會重新讀取。 */
@Composable
fun TodayStepsCard(
    state: TodaySteps,
    onRefresh: () -> Unit,
    syncMessage: String?,
    onSyncToWeb: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.today_steps_title), style = MaterialTheme.typography.titleMedium)
                Row {
                    TextButton(onClick = onSyncToWeb) { Text(stringResource(R.string.today_steps_sync_button)) }
                    TextButton(onClick = onRefresh) { Text(stringResource(R.string.today_steps_refresh)) }
                }
            }
            syncMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            when (state) {
                TodaySteps.Loading -> Hint(stringResource(R.string.today_steps_loading))
                TodaySteps.SensorSource -> Hint(stringResource(R.string.today_steps_sensor_source))
                TodaySteps.Unavailable -> Hint(stringResource(R.string.today_steps_unavailable))
                TodaySteps.NoPermission -> Hint(stringResource(R.string.today_steps_no_permission))
                is TodaySteps.Error -> Hint(stringResource(R.string.today_steps_error, state.message))
                is TodaySteps.Value -> {
                    Text(
                        text = "%,d".format(state.primary),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.today_steps_source, stringResource(sourceLabel(state.source))),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.primary == 0L) Hint(stringResource(R.string.today_steps_no_data))

                    // 各來源分開列出；合計由 Health Connect 去除重複，不是三個數字相加。
                    Detail(stringResource(R.string.today_steps_row_zepp, state.zepp?.let { "%,d".format(it) } ?: "—"))
                    Detail(stringResource(R.string.today_steps_row_google, state.googleFit?.let { "%,d".format(it) } ?: "—"))
                    Detail(stringResource(R.string.today_steps_row_phone, state.phone?.let { "%,d".format(it) } ?: "—"))
                    state.others.forEach { (pkg, steps) ->
                        Detail(stringResource(R.string.today_steps_row_other, pkg, "%,d".format(steps)))
                    }
                    // Health Connect 裡沒有出現小米／Zepp Life：不是顯示錯誤，而是它根本沒寫入。
                    if (state.zepp == null && state.googleFit == null) Hint(stringResource(R.string.today_steps_zepp_missing))
                    state.total?.let { total ->
                        Detail(stringResource(R.string.today_steps_row_total, "%,d".format(total)))
                        Text(
                            stringResource(R.string.today_steps_total_note),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Detail(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun sourceLabel(source: StepSource): Int = when (source) {
    StepSource.ZEPP_LIFE -> StepSourceTag.labelRes(StepSourceTag.ZEPP_LIFE)
    StepSource.GOOGLE_FIT -> StepSourceTag.labelRes(StepSourceTag.GOOGLE_FIT)
    StepSource.DEVICE_SENSOR -> StepSourceTag.labelRes(StepSourceTag.SENSOR)
}
