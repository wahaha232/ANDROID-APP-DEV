package com.startinsnow.gpstracker.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun HomeScreen(
    onStartRecording: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val state by viewModel.recordingState.collectAsState()
    val tracks by viewModel.recentTracks.collectAsState()

    Scaffold { padding: PaddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("GPS TRACKER", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("目前狀態", style = MaterialTheme.typography.labelLarge)
            Text("${state.gpsQuality.emoji} ${state.accuracyLabel}", style = MaterialTheme.typography.bodyLarge)
            Text("速度 ${"%.1f".format(state.currentSpeedKmh)} km/h")
            Text("距離 ${"%.2f".format(state.distanceMeters / 1000.0)} km")
            Text("步數 ${state.stepCount}")

            Button(onClick = onStartRecording, modifier = Modifier.fillMaxWidth()) {
                Text("開始記錄")
            }
            OutlinedButton(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) {
                Text("歷史紀錄（${tracks.size}）")
            }
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("設定")
            }
        }
    }
}
