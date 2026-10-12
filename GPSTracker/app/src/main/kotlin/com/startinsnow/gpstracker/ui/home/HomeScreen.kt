package com.startinsnow.gpstracker.ui.home

import com.startinsnow.gpstracker.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.startinsnow.gpstracker.core.model.TrackStatus
import com.startinsnow.gpstracker.data.db.TrackEntity
import com.startinsnow.gpstracker.service.RecordingUiState
import com.startinsnow.gpstracker.ui.components.EmptyState
import com.startinsnow.gpstracker.ui.components.InfoBanner
import com.startinsnow.gpstracker.ui.components.SectionHeader
import com.startinsnow.gpstracker.ui.components.formatDuration
import com.startinsnow.gpstracker.ui.components.formatDurationShort
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    onStartRecording: () -> Unit,
    onResumeRecording: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenTrack: (String) -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by viewModel.recordingState.collectAsStateWithLifecycle()
    val tracks by viewModel.recentTracks.collectAsStateWithLifecycle()
    val pendingRecovery by viewModel.pendingRecovery.collectAsStateWithLifecycle()
    val isActive = state.status == TrackStatus.RECORDING || state.status == TrackStatus.PAUSED
    val dateFormat = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("GPS Tracker", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { StatusHero(state = state, isActive = isActive) }

            item {
                Button(
                    onClick = onStartRecording,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    Icon(if (isActive) Icons.Filled.PlayArrow else Icons.Filled.MyLocation, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isActive) stringResource(R.string.home_001) else stringResource(R.string.home_002),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            pendingRecovery?.let { candidate ->
                item {
                    RecoveryCard(
                        candidate = candidate,
                        onResume = { onResumeRecording(candidate.trackId) },
                        onAbandon = { viewModel.abandonPendingRecovery() }
                    )
                }
            }

            state.message?.let { message ->
                item {
                    InfoBanner(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        icon = Icons.Filled.ErrorOutline,
                        onDismiss = { com.startinsnow.gpstracker.service.TrackingStateHolder.clearMessage() }
                    )
                }
            }

            item {
                SectionHeader(
                    title = context.getString(R.string.home_003),
                    actionText = if (tracks.isNotEmpty()) context.getString(R.string.home_004) else null,
                    onAction = onOpenHistory
                )
            }

            if (tracks.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.Map,
                        title = context.getString(R.string.home_005),
                        subtitle = context.getString(R.string.home_006)
                    )
                }
            } else {
                items(tracks.take(3), key = { it.trackId }) { track ->
                    RecentTrackRow(track = track, dateFormat = dateFormat, onClick = { onOpenTrack(track.trackId) })
                }
            }
        }
    }
}

@Composable
private fun StatusHero(state: RecordingUiState, isActive: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val fg = MaterialTheme.colorScheme.onPrimary
    val fgMuted = fg.copy(alpha = 0.85f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(
                Brush.linearGradient(
                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
                )
            )
            .padding(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (state.hasFix) Color.White else Color.White.copy(alpha = 0.5f))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(state.accuracyLabel, style = MaterialTheme.typography.labelLarge, color = fg)
                }
                Text(
                    "${state.movementMode.emoji} ${state.movementMode.name}",
                    style = MaterialTheme.typography.labelLarge,
                    color = fg
                )
            }

            if (isActive) {
                Column {
                    Text(
                        "%.2f".format(state.distanceMeters / 1000.0),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = fg
                    )
                    Text(
                        stringResource(R.string.home_007) + if (state.isAutoPaused) stringResource(R.string.home_008) else stringResource(R.string.home_009),
                        style = MaterialTheme.typography.bodyMedium,
                        color = fgMuted
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    HeroStat(formatDuration(state.durationMs), context.getString(R.string.home_010), fg)
                    HeroStat("%.1f".format(state.currentSpeedKmh), "km/h", fg)
                    HeroStat(state.stepCount.toString(), context.getString(R.string.settings_019), fg)
                }
            } else {
                Column {
                    Text(stringResource(R.string.home_011), style = MaterialTheme.typography.headlineMedium, color = fg)
                    Text(
                        stringResource(R.string.home_012),
                        style = MaterialTheme.typography.bodyMedium,
                        color = fgMuted
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    HeroStat(state.pointCount.toString(), context.getString(R.string.home_013), fg)
                    HeroStat(if (state.hasFix) context.getString(R.string.home_014) else context.getString(R.string.home_015), context.getString(R.string.home_016), fg)
                    HeroStat(if (state.isStepSensorAvailable) context.getString(R.string.home_017) else context.getString(R.string.home_018), context.getString(R.string.home_019), fg)
                }
            }
        }
    }
}

@Composable
private fun HeroStat(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color.copy(alpha = 0.8f)
        )
    }
}

@Composable
private fun RecoveryCard(
    candidate: com.startinsnow.gpstracker.service.TrackingStateHolder.RecoveryCandidate,
    onResume: () -> Unit,
    onAbandon: () -> Unit
) {
    val dateText = remember(candidate.startTimeMs) {
        SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(candidate.startTimeMs))
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.home_020),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Text(
                stringResource(R.string.home_021, dateText, candidate.pointCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.home_022)) }
            OutlinedButton(onClick = onAbandon, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.home_023)) }
        }
    }
}

@Composable
private fun RecentTrackRow(
    track: TrackEntity,
    dateFormat: SimpleDateFormat,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(track.dominantMode.emoji, fontSize = 22.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    dateFormat.format(Date(track.startTimeMs)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "%.2f km ・ %s".format(track.distanceMeters / 1000.0, formatDurationShort(track.durationMs)),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
