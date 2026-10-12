package com.startinsnow.gpstracker.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.startinsnow.gpstracker.R
import com.startinsnow.gpstracker.data.db.TrackEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 網站「紀錄管理」刪除了先前從這支手機上傳的紀錄，但手機上還留著：
 * 絕不自動刪除，由使用者決定「一起從手機移除」或「保留在手機」（保留後不再提示）。
 */
@Composable
fun DeletedOnWebCard(
    tracks: List<TrackEntity>,
    onRemove: () -> Unit,
    onKeep: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.web_deleted_title, tracks.size), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.web_deleted_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            tracks.sortedByDescending { it.startTimeMs }.take(5).forEach { t ->
                Text(
                    "• ${dateFormat.format(Date(t.startTimeMs))}  ${"%.2f".format(t.distanceMeters / 1000.0)} km",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (tracks.size > 5) {
                Text(stringResource(R.string.web_deleted_more, tracks.size - 5), style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onRemove,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.web_deleted_remove)) }
                OutlinedButton(onClick = onKeep, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.web_deleted_keep))
                }
            }
        }
    }
}
