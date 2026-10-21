package com.startinsnow.healthtracker.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.collectAsState
import com.startinsnow.healthtracker.data.prefs.UserSettings
import com.startinsnow.healthtracker.data.prefs.UserSettingsRepository
import com.startinsnow.healthtracker.reminder.ReminderScheduler
import com.startinsnow.healthtracker.update.UpdateIntervals
import com.startinsnow.healthtracker.update.UpdateLogicAuto
import com.startinsnow.healthtracker.update.UpdateScheduler
import com.startinsnow.healthtracker.update.UpdateState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.startinsnow.healthtracker.BuildConfig
import com.startinsnow.healthtracker.cloud.CloudException
import com.startinsnow.healthtracker.cloud.CloudSync
import com.startinsnow.healthtracker.update.AppUpdater
import com.startinsnow.healthtracker.update.UpdateInfo
import com.startinsnow.healthtracker.update.UpdateLogic
import kotlinx.coroutines.launch

/** 設定「關於」裡的雲端更新：檢查你自己雲端放的新版、下載、交給系統安裝（資料保留）。 */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
fun UpdateSection(cloud: CloudSync, settings: UserSettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = cloud.engine
    val updater = remember(engine) { engine?.let { AppUpdater(it, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY) } }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var latest by remember { mutableStateOf<UpdateInfo?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    val user by settings.settings.collectAsState(initial = UserSettings())
    // 背景自動下載好、還沒安裝的新版：直接顯示安裝按鈕
    val pendingName = remember {
        UpdateState.downloadedName(context).takeIf {
            UpdateLogicAuto.pendingInstall(UpdateState.downloadedCode(context), AppUpdater.apkFile(context).isFile, BuildConfig.VERSION_CODE)
        }
    }
    var downloaded by remember { mutableStateOf(pendingName != null) }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) message = "沒有通知權限：自動檢查仍會下載新版，但不會跳通知。可到手機設定開啟通知。"
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("App 更新", style = MaterialTheme.typography.titleMedium)
        Text("從你自己的雲端（需先登入）取得新版，資料會保留。安裝前系統會請你確認。", style = MaterialTheme.typography.bodySmall)
        Text("自動檢查間隔", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UpdateIntervals.OPTIONS.forEach { h ->
                FilterChip(selected = user.updateIntervalHours == h, onClick = {
                    scope.launch { settings.saveUpdateInterval(h) }
                    UpdateScheduler.apply(context, h)
                    if (h > 0 && !ReminderScheduler.hasNotificationPermission(context) && Build.VERSION.SDK_INT >= 33) {
                        askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }, label = { Text(UpdateIntervals.label(h)) })
            }
        }
        Text(
            if (user.updateIntervalHours == 0) "目前只手動檢查。選一個間隔就會在背景自動檢查：有新版會先下載（只在 Wi‑Fi）並通知你，安裝仍需你在系統畫面確認。"
            else "背景自動檢查中（${UpdateIntervals.label(user.updateIntervalHours)}，只在 Wi‑Fi）。發現新版會先下載並通知你，點通知回到這裡安裝。",
            style = MaterialTheme.typography.bodySmall,
        )
        UpdateState.lastCheck(context)?.let { (at, msg) ->
            Text("上次自動檢查：" + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.TAIWAN).format(java.util.Date(at)) + "　" + msg, style = MaterialTheme.typography.bodySmall)
        }
        if (updater == null) {
            Text("這個版本沒有設定雲端，無法檢查更新。", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        OutlinedButton(enabled = !busy, onClick = {
            busy = true; message = "檢查中…"; downloaded = false; progress = null
            scope.launch {
                try {
                    if (engine?.currentSession() == null) {
                        message = "請先到「雲端同步」登入"
                    } else {
                        val info = updater.fetchLatest()
                        latest = info
                        message = when {
                            info == null -> "雲端還沒有放新版本"
                            UpdateLogic.isNewer(info, BuildConfig.VERSION_CODE) -> "有新版本 ${info.versionName}（${info.versionCode}）" + info.notes.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
                            else -> "目前已是最新版（${BuildConfig.VERSION_NAME}）"
                        }
                    }
                } catch (e: CloudException) {
                    message = e.message
                } catch (e: Exception) {
                    message = "檢查失敗：${e.message}"
                } finally {
                    busy = false
                }
            }
        }) { Text(if (busy && progress == null) "處理中…" else "檢查更新") }

        val info = latest
        if (info != null && UpdateLogic.isNewer(info, BuildConfig.VERSION_CODE)) {
            if (!downloaded) {
                Button(enabled = !busy, onClick = {
                    busy = true; progress = 0f; message = "下載中…"
                    scope.launch {
                        try {
                            updater.download(info, AppUpdater.apkFile(context)) { done, total ->
                                progress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
                            }
                            downloaded = true
                            message = "下載完成並通過檢查，可以安裝"
                        } catch (e: CloudException) {
                            message = e.message
                        } catch (e: Exception) {
                            message = "下載失敗：${e.message}"
                        } finally {
                            busy = false
                            progress = null
                        }
                    }
                }) { Text("下載新版本") }
            } else {
                Button(onClick = {
                    if (!AppUpdater.canInstall(context)) {
                        message = "請在接下來的畫面允許「安裝未知應用程式」，回來後再按一次「安裝新版本」"
                        AppUpdater.openInstallPermissionSettings(context)
                    } else {
                        AppUpdater.install(context, AppUpdater.apkFile(context))
                    }
                }) { Text("安裝新版本") }
            }
        }
        progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
