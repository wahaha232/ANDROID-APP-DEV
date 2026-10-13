package com.startinsnow.gpstracker.ui.recap

import android.app.Application
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.startinsnow.gpstracker.GpsTrackerApplication
import com.startinsnow.gpstracker.R
import com.startinsnow.gpstracker.core.analysis.RecapCalculator
import com.startinsnow.gpstracker.core.analysis.RecapResult
import com.startinsnow.gpstracker.core.analysis.TrackSummary
import com.startinsnow.gpstracker.core.model.MovementMode
import com.startinsnow.gpstracker.core.model.TrackStatus
import com.startinsnow.gpstracker.ui.components.formatDuration
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.math.abs

/** 活動回顧的資料：已結束的活動摘要（與網站回顧頁同一套規則，由 [RecapCalculator] 計算）。 */
class RecapViewModel(application: Application) : AndroidViewModel(application) {
    private val app get() = getApplication<GpsTrackerApplication>()

    val tracks: StateFlow<List<TrackSummary>> = app.trackRepository.observeTracks()
        .map { list ->
            list.filter { it.status == TrackStatus.FINISHED }.map {
                TrackSummary(
                    trackId = it.trackId, startTimeMs = it.startTimeMs, distanceMeters = it.distanceMeters,
                    durationMs = it.durationMs, ascentMeters = it.totalAscentMeters, steps = it.stepCount,
                    maxSpeedMps = it.maxSpeedMps, mode = it.dominantMode.name
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun RecapScreen(onBack: () -> Unit, viewModel: RecapViewModel = viewModel()) {
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val years = remember(tracks) { RecapCalculator.years(tracks) }
    var pickedYear by rememberSaveable { mutableIntStateOf(0) } // 0 = 尚未選擇，預設今年（沒有今年就用最新的一年）
    var month by rememberSaveable { mutableIntStateOf(0) }       // 0 = 全年
    val year = if (pickedYear in years) pickedYear else (years.firstOrNull { it == LocalDate.now().year } ?: years.firstOrNull())

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.recap_title), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (year == null) {
                item { Text(stringResource(R.string.recap_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                return@LazyColumn
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(years) { y ->
                        FilterChip(selected = y == year, onClick = { pickedYear = y }, label = { Text(stringResource(R.string.recap_year_fmt, y)) })
                    }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items((0..12).toList()) { m ->
                        FilterChip(
                            selected = m == month, onClick = { month = m },
                            label = { Text(if (m == 0) stringResource(R.string.recap_all_year) else stringResource(R.string.recap_month_fmt, m)) }
                        )
                    }
                }
            }
            val result = RecapCalculator.compute(tracks, year, month)
            if (result.totals.count == 0) {
                item { Text(stringResource(R.string.recap_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                recapContent(result)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.recapContent(r: RecapResult) {
    item { StatsCard(r) }
    item { ChartCard(r) }
    item { ModesCard(r) }
    item { HighlightsCard(r) }
    item { TopCard(r) }
}

private fun fmt1(v: Double) = "%,.1f".format(v)

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Line(label: String, value: String, sub: String? = null) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 與上一期比較；上一期沒有活動時只說沒有，不顯示百分比。 */
@Composable
private fun deltaText(cur: Double, prev: Double, unit: String, prevLabel: String): String {
    if (prev <= 0.0) return stringResource(R.string.recap_prev_none, prevLabel)
    val diff = cur - prev
    val sign = if (diff > 0) "+" else if (diff < 0) "−" else ""
    val pct = Math.abs(Math.round(diff / prev * 100))
    return stringResource(R.string.recap_prev_delta, prevLabel, sign + fmt1(abs(diff)) + unit, "$sign$pct%")
}

@Composable
private fun StatsCard(r: RecapResult) {
    val t = r.totals
    val prevLabel = if (r.month == 0) stringResource(R.string.recap_year_fmt, r.previousYear) else stringResource(R.string.recap_month_fmt, r.previousMonth)
    SectionCard(stringResource(R.string.recap_title)) {
        Line(stringResource(R.string.recap_km), "${fmt1(t.km)} km", deltaText(t.km, r.previous.km, " km", prevLabel))
        Line(stringResource(R.string.recap_count), "${t.count}", deltaText(t.count.toDouble(), r.previous.count.toDouble(), "", prevLabel))
        Line(stringResource(R.string.recap_active_days), "${t.activeDays}", if (r.streakDays > 1) stringResource(R.string.recap_streak, r.streakDays) else null)
        Line(stringResource(R.string.recap_duration), formatDuration(t.durationMs))
        if (t.ascentMeters > 0) Line(stringResource(R.string.recap_ascent), "%,.0f m".format(t.ascentMeters))
        if (t.steps > 0) Line(stringResource(R.string.recap_steps), "%,d".format(t.steps))
    }
}

@Composable
private fun ChartCard(r: RecapResult) {
    val max = r.buckets.maxOf { it.km }
    SectionCard(stringResource(if (r.month == 0) R.string.recap_chart_month else R.string.recap_chart_day)) {
        Row(Modifier.fillMaxWidth().height(140.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            r.buckets.forEach { b ->
                val frac = if (max > 0) (b.km / max).toFloat() else 0f
                Box(
                    Modifier.weight(1f).fillMaxHeight(if (b.km > 0) frac.coerceIn(0.02f, 1f) else 0f)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            r.buckets.forEachIndexed { i, b ->
                // 每日圖只標每 5 天一個數字，避免擠在一起。
                val show = r.month == 0 || b.label == 1 || b.label % 5 == 0
                Text(
                    if (show) b.label.toString() else "", modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                )
            }
        }
    }
}

private fun modeOf(name: String) = runCatching { MovementMode.valueOf(name) }.getOrDefault(MovementMode.UNKNOWN)

@StringRes
private fun modeLabelRes(mode: MovementMode): Int = when (mode) {
    MovementMode.WALKING -> R.string.mode_walking
    MovementMode.RUNNING -> R.string.mode_running
    MovementMode.BICYCLE -> R.string.mode_bicycle
    MovementMode.MOTORCYCLE -> R.string.mode_motorcycle
    MovementMode.CAR -> R.string.mode_car
    MovementMode.PUBLIC_TRANSPORT -> R.string.mode_public_transport
    MovementMode.AIRPLANE -> R.string.mode_airplane
    else -> R.string.mode_other
}

@Composable
private fun ModesCard(r: RecapResult) {
    SectionCard(stringResource(R.string.recap_modes)) {
        r.modes.forEach { m ->
            val mode = modeOf(m.mode)
            Line(mode.emoji + " " + stringResource(modeLabelRes(mode)), "${fmt1(m.km)} km", "${Math.round(m.percent)}% ・ ${m.count}")
        }
    }
}

@Composable
private fun HighlightsCard(r: RecapResult) {
    val df = remember { SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()) }
    SectionCard(stringResource(R.string.recap_highlights)) {
        r.longest?.let { Line(stringResource(R.string.recap_longest), "${fmt1(it.distanceMeters / 1000)} km", df.format(Date(it.startTimeMs))) }
        r.fastest?.let { Line(stringResource(R.string.recap_fastest), "${fmt1(it.maxSpeedMps * 3.6)} km/h", df.format(Date(it.startTimeMs))) }
        r.busiestDay?.let { Line(stringResource(R.string.recap_busiest), "${fmt1(it.km)} km", it.date.toString().replace('-', '/') + " ・ ${it.count}") }
        Text(stringResource(R.string.recap_speed_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TopCard(r: RecapResult) {
    val df = remember { SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()) }
    SectionCard(stringResource(R.string.recap_top)) {
        r.top.forEachIndexed { i, t -> Line("${i + 1}. " + df.format(Date(t.startTimeMs)), "${fmt1(t.distanceMeters / 1000)} km") }
    }
}
