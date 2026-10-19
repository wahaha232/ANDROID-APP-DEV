package com.startinsnow.healthtracker.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.startinsnow.healthtracker.data.prefs.UserSettings
import com.startinsnow.healthtracker.data.prefs.UserSettingsRepository
import com.startinsnow.healthtracker.reminder.ReminderLogic
import com.startinsnow.healthtracker.reminder.ReminderScheduler
import com.startinsnow.healthtracker.security.AppLock
import kotlinx.coroutines.launch

/** 設定「提醒與安全」：量測提醒（本機通知）與 App 鎖。 */
@Composable
fun ReminderLockSection(repo: UserSettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by repo.settings.collectAsState(initial = UserSettings())
    var message by remember { mutableStateOf<String?>(null) }
    var showPicker by remember { mutableStateOf(false) }

    fun saveReminders(enabled: Boolean, times: List<Int>, skip: Boolean) {
        scope.launch { repo.saveReminders(enabled, times, skip) }
        ReminderScheduler.apply(context, enabled, times.distinct().sorted())
    }

    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) saveReminders(true, s.reminderTimes, s.reminderSkipIfRecorded)
        else message = "沒有通知權限，提醒無法顯示。可到手機設定 > 應用程式 > 血糖記錄 > 通知開啟。"
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("量測提醒", style = MaterialTheme.typography.titleLarge)
        Text("每天在你設定的時間跳出通知，只提醒、不判斷任何數值。通知都在手機本機產生。", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("啟用提醒")
            Switch(checked = s.reminderEnabled, onCheckedChange = { on ->
                message = null
                if (on && !ReminderScheduler.hasNotificationPermission(context) && Build.VERSION.SDK_INT >= 33) {
                    askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else saveReminders(on, s.reminderTimes, s.reminderSkipIfRecorded)
            })
        }
        s.reminderTimes.forEach { m ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(ReminderLogic.label(m), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { saveReminders(s.reminderEnabled, s.reminderTimes - m, s.reminderSkipIfRecorded) }) { Text("移除") }
            }
        }
        if (s.reminderTimes.size < ReminderLogic.MAX_TIMES) {
            OutlinedButton(onClick = { showPicker = true }) { Text("新增提醒時間") }
        } else Text("最多 ${ReminderLogic.MAX_TIMES} 個提醒時間", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = s.reminderSkipIfRecorded, onCheckedChange = { saveReminders(s.reminderEnabled, s.reminderTimes, it) })
            Text("今天已記錄血糖就不提醒")
        }

        Text("App 鎖", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
        Text("開啟後，進入 App 需要指紋、臉部或手機螢幕鎖定 PIN；離開超過 2 分鐘會重新上鎖。", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("開啟 App 鎖")
            Switch(checked = s.appLock, onCheckedChange = { on ->
                val act = context as? FragmentActivity
                message = null
                when {
                    act == null -> message = "無法開啟驗證畫面"
                    !AppLock.available(act) -> message = "手機尚未設定螢幕鎖定（指紋或 PIN），請先到系統設定建立。"
                    else -> AppLock.prompt(act, if (on) "確認開啟 App 鎖" else "確認關閉 App 鎖") { ok ->
                        if (ok) scope.launch { repo.saveAppLock(on) }
                    }
                }
            })
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    if (showPicker) {
        val state = rememberTimePickerState(initialHour = 8, initialMinute = 0, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showPicker = false },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    saveReminders(s.reminderEnabled, s.reminderTimes + (state.hour * 60 + state.minute), s.reminderSkipIfRecorded)
                    showPicker = false
                }) { Text("加入") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("取消") } },
        )
    }
}

/** 上鎖時的畫面：不顯示任何資料。 */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("血糖記錄", style = MaterialTheme.typography.headlineMedium)
        Text("已上鎖", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 12.dp))
        Button(onClick = onUnlock) { Text("解鎖") }
    }
}
