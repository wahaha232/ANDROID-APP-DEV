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
import com.startinsnow.gpstracker.core.model.TrackStatus

@Composable
fun HomeScreen(
    onStartRecording: () -> Unit,
    onResumeRecording: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val state by viewModel.recordingState.collectAsState()
    val tracks by viewModel.recentTracks.collectAsState()
    val pendingRecovery by viewModel.pendingRecovery.collectAsState()

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

            if (state.status == TrackStatus.RECORDING || state.status == TrackStatus.PAUSED) {
                Text(
                    if (state.isAutoPaused) "⏸ 記錄中（已自動暫停）" else "🔴 記錄中（已記錄 ${state.pointCount} 個定位點）",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            state.message?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            pendingRecovery?.let { candidate ->
                Text(
                    "發現上次未正常結束的記錄（" +
                        "${java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(candidate.startTimeMs))}，" +
                        "${candidate.pointCount} 個定位點）",
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = { onResumeRecording(candidate.trackId) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("接續這筆記錄") }
                OutlinedButton(
                    onClick = { viewModel.abandonPendingRecovery() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("結束這筆未完成記錄") }
            }

            Button(onClick = onStartRecording, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.status == TrackStatus.RECORDING || state.status == TrackStatus.PAUSED) "回到記錄畫面" else "開始記錄")
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
