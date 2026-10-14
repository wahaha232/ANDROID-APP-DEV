package com.startinsnow.gpstracker.ui.history

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.startinsnow.gpstracker.core.model.MovementMode
import com.startinsnow.gpstracker.core.model.UploadStatus
import com.startinsnow.gpstracker.data.db.TrackEntity
import com.startinsnow.gpstracker.ui.components.EmptyState
import com.startinsnow.gpstracker.ui.components.StatusChip
import com.startinsnow.gpstracker.ui.components.formatDurationShort
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    onOpenTrack: (String) -> Unit,
    viewModel: HistoryViewModel = viewModel()
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val tracksOrNull by viewModel.tracks.collectAsStateWithLifecycle()
    val editsRepo = remember { com.startinsnow.gpstracker.data.prefs.TrackEditsRepository(androidx.compose.ui.platform.LocalContext.current) }
    val edits by editsRepo.edits.collectAsStateWithLifecycle(emptyMap())
    val tracksLoaded = tracksOrNull != null
    val tracks = tracksOrNull ?: emptyList()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_001), style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = filter.mode == null,
                        onClick = { viewModel.setModeFilter(null) },
                        label = { Text(stringResource(R.string.history_002)) }
                    )
                }
                items(MovementMode.entries) { mode ->
                    FilterChip(
                        selected = filter.mode == mode,
                        onClick = { viewModel.setModeFilter(if (filter.mode == mode) null else mode) },
                        label = { Text("${mode.emoji} ${mode.name}") }
                    )
                }
                item {
                    FilterChip(
                        selected = filter.onlyWithPhotos,
                        onClick = { viewModel.setOnlyWithPhotos(!filter.onlyWithPhotos) },
                        label = { Text(stringResource(R.string.history_003)) }
                    )
                }
            }

            if (tracksLoaded && tracks.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.History,
                    title = context.getString(R.string.history_004),
                    subtitle = context.getString(R.string.history_005),
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(tracks, key = { it.trackId }) { track ->
                        TrackCard(track = track, edit = edits[track.trackId], dateFormat = dateFormat, onClick = { onOpenTrack(track.trackId) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackCard(track: TrackEntity, edit: com.startinsnow.gpstracker.core.edits.TrackEdit?, dateFormat: SimpleDateFormat, onClick: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 使用者在 App／網站改過的移動方式優先顯示（原始判斷仍保留在資料裡）。
    val shownMode = edit?.mode?.let { m -> runCatching { com.startinsnow.gpstracker.core.model.MovementMode.valueOf(m) }.getOrNull() } ?: track.dominantMode
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(shownMode.emoji, fontSize = 20.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            dateFormat.format(Date(track.startTimeMs)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            edit?.title?.takeIf { it.isNotBlank() } ?: shownMode.name,
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                }
                UploadBadge(track.uploadStatus)
            }

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "%.2f".format(track.distanceMeters / 1000.0),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "km",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MiniStat(formatDurationShort(track.durationMs), context.getString(R.string.home_010))
                MiniStat("%.1f".format(track.avgSpeedMps * 3.6), context.getString(R.string.history_006))
                MiniStat(track.stepCount.toString(), context.getString(R.string.settings_019))
                MiniStat(track.photoCount.toString(), context.getString(R.string.detail_016))
            }
        }
    }
}

@Composable
private fun MiniStat(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun UploadBadge(status: UploadStatus) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val color: Color = when (status) {
        UploadStatus.NOT_UPLOADED -> MaterialTheme.colorScheme.onSurfaceVariant
        UploadStatus.UPLOADING -> MaterialTheme.colorScheme.tertiary
        UploadStatus.UPLOADED -> MaterialTheme.colorScheme.secondary
        UploadStatus.FAILED -> MaterialTheme.colorScheme.error
    }
    val text = when (status) {
        UploadStatus.NOT_UPLOADED -> context.getString(R.string.history_007)
        UploadStatus.UPLOADING -> context.getString(R.string.history_008)
        UploadStatus.UPLOADED -> context.getString(R.string.history_009)
        UploadStatus.FAILED -> context.getString(R.string.history_010)
    }
    StatusChip(text = text, color = color)
}
