package com.startinsnow.gpstracker.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.startinsnow.gpstracker.data.db.TrackEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    onOpenTrack: (String) -> Unit,
    viewModel: HistoryViewModel = viewModel()
) {
    val tracks by viewModel.tracks.collectAsState()
    val dateFormat = rememberDateFormat()

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(tracks, key = { it.trackId }) { track ->
                TrackRow(track, dateFormat, onClick = { onOpenTrack(track.trackId) })
            }
        }
    }
}

@Composable
private fun TrackRow(track: TrackEntity, dateFormat: SimpleDateFormat, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(dateFormat.format(Date(track.startTimeMs)), style = MaterialTheme.typography.labelMedium)
            Text(
                "${track.dominantMode.emoji} ${"%.1f".format(track.distanceMeters / 1000.0)} km",
                style = MaterialTheme.typography.titleMedium
            )
            Text("${"%.1f".format(track.avgSpeedMps * 3.6)} km/h 平均 · ${"%.1f".format(track.maxSpeedMps * 3.6)} km/h 最高")
            Text("${track.stepCount} steps · ${track.photoCount} photos")
            TextButton(onClick = onClick) { Text("查看 / 回放") }
        }
    }
}

@Composable
private fun rememberDateFormat(): SimpleDateFormat =
    androidx.compose.runtime.remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()) }
