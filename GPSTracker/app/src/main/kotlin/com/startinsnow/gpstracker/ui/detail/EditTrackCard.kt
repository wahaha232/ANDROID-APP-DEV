package com.startinsnow.gpstracker.ui.detail

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.startinsnow.gpstracker.R
import com.startinsnow.gpstracker.core.edits.TrackEdit
import com.startinsnow.gpstracker.core.model.MovementMode

/** App 內可選的移動方式（不含「靜止」）；與網站紀錄管理的選項一致。 */
private val EDIT_MODES = listOf(
    MovementMode.WALKING, MovementMode.RUNNING, MovementMode.BICYCLE, MovementMode.MOTORCYCLE,
    MovementMode.CAR, MovementMode.PUBLIC_TRANSPORT, MovementMode.AIRPLANE, MovementMode.UNKNOWN
)

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

/**
 * 紀錄的「標題／備註／移動方式」編輯。儲存後會自動同步到記錄網頁（見 TrackEditsSync）；
 * [statusText] 顯示儲存與同步的結果。原始 GPS 紀錄與統計不會被修改。
 */
@Composable
fun EditTrackCard(
    edit: TrackEdit?,
    appMode: MovementMode,
    statusText: String?,
    onSave: (title: String, notes: String, mode: String?) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.edit_title), style = MaterialTheme.typography.titleMedium)
            val shown = edit != null && !edit.isBlank
            if (shown) {
                if (edit!!.title.isNotBlank()) Text(edit.title, style = MaterialTheme.typography.titleLarge)
                if (edit.notes.isNotBlank()) Text(edit.notes, style = MaterialTheme.typography.bodyMedium)
                edit.mode?.let { m ->
                    val mode = runCatching { MovementMode.valueOf(m) }.getOrDefault(MovementMode.UNKNOWN)
                    Text(
                        stringResource(R.string.edit_mode_override, mode.emoji + " " + stringResource(modeLabelRes(mode))),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary
                    )
                }
                if (edit.dirty) Text(stringResource(R.string.edit_pending), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            } else {
                Text(stringResource(R.string.edit_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            statusText?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            OutlinedButton(onClick = { showDialog = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.edit_button)) }
        }
    }
    if (showDialog) EditTrackDialog(edit, appMode, onDismiss = { showDialog = false }, onSave = { t, n, m -> showDialog = false; onSave(t, n, m) })
}

@Composable
private fun EditTrackDialog(edit: TrackEdit?, appMode: MovementMode, onDismiss: () -> Unit, onSave: (String, String, String?) -> Unit) {
    var title by remember { mutableStateOf(edit?.title ?: "") }
    var notes by remember { mutableStateOf(edit?.notes ?: "") }
    var mode by remember { mutableStateOf(edit?.mode) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it.take(80) }, label = { Text(stringResource(R.string.edit_field_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = notes, onValueChange = { notes = it.take(1000) }, label = { Text(stringResource(R.string.edit_field_notes)) }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.edit_field_mode), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == null, onClick = { mode = null },
                        label = { Text(stringResource(R.string.edit_mode_keep, appMode.emoji + " " + stringResource(modeLabelRes(appMode)))) }
                    )
                    EDIT_MODES.forEach { m ->
                        FilterChip(selected = mode == m.name, onClick = { mode = m.name }, label = { Text(m.emoji + " " + stringResource(modeLabelRes(m))) })
                    }
                }
                Text(stringResource(R.string.edit_dialog_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(title, notes, mode) }) { Text(stringResource(R.string.edit_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.autopause_cancel)) } }
    )
}
