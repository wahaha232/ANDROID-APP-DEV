package com.startinsnow.gpstracker.update

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.startinsnow.gpstracker.BuildConfig
import com.startinsnow.gpstracker.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 更新流程的狀態：設定頁的「App 更新」和啟動時的自動提示共用。 */
class UpdateController(private val context: Context, private val scope: CoroutineScope) {
    private val updater = AppUpdater()
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var latest by mutableStateOf<RemoteUpdate?>(null)
    var progress by mutableStateOf<Float?>(null)
    var downloading by mutableStateOf(false)
    var downloaded by mutableStateOf(false)

    fun hasNew(): Boolean = latest?.let { UpdateLogic.isNewer(BuildConfig.VERSION_NAME, it.versionName) } == true

    /** 查最新版。[silent] 時不顯示任何訊息，只有找到新版才呼叫 [onFound]。 */
    fun check(silent: Boolean, onFound: (RemoteUpdate) -> Unit = {}) {
        if (busy) return
        busy = true
        downloaded = false
        if (!silent) message = context.getString(R.string.update_checking)
        scope.launch {
            try {
                val info = updater.fetchLatest()
                latest = info
                if (UpdateLogic.isNewer(BuildConfig.VERSION_NAME, info.versionName)) {
                    if (!silent) message = context.getString(R.string.update_found, info.versionName, BuildConfig.VERSION_NAME)
                    onFound(info)
                } else if (!silent) {
                    message = context.getString(R.string.update_latest, BuildConfig.VERSION_NAME)
                }
            } catch (e: Exception) {
                if (!silent) message = context.getString(R.string.update_failed, e.message.orEmpty())
            } finally {
                busy = false
            }
        }
    }

    fun download(autoInstall: Boolean) {
        val info = latest ?: return
        if (busy) return
        busy = true
        downloading = true
        progress = 0f
        message = context.getString(R.string.update_downloading)
        scope.launch {
            try {
                val file = AppUpdater.apkFile(context)
                updater.download(info, file) { progress = it }
                val problem = updater.verifyArchive(context, file)
                if (problem != null) {
                    file.delete()
                    message = context.getString(R.string.update_failed, problem)
                } else {
                    downloaded = true
                    message = context.getString(R.string.update_downloaded)
                    if (autoInstall) install()
                }
            } catch (e: Exception) {
                message = context.getString(R.string.update_failed, e.message.orEmpty())
            } finally {
                busy = false
                downloading = false
                progress = null
            }
        }
    }

    fun install() {
        if (!AppUpdater.canInstall(context)) {
            message = context.getString(R.string.update_allow)
            AppUpdater.openInstallPermissionSettings(context)
        } else {
            AppUpdater.install(context, AppUpdater.apkFile(context))
        }
    }
}

private const val KEY_INTERVAL_HOURS = "interval_hours"
private const val DEFAULT_INTERVAL_HOURS = 24

/** 自動檢查的間隔選項（小時）：0＝每次開啟、負數＝不自動檢查。 */
private val INTERVAL_OPTIONS = listOf(
    0 to R.string.update_interval_launch,
    6 to R.string.update_interval_6h,
    24 to R.string.update_interval_daily,
    72 to R.string.update_interval_3d,
    168 to R.string.update_interval_weekly,
    -1 to R.string.update_interval_never,
)

/** 設定頁的「App 更新」區塊。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { UpdateController(context.applicationContext, scope) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            androidx.compose.ui.res.stringResource(R.string.update_hint, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(enabled = !controller.busy, onClick = { controller.check(silent = false) }) {
            Text(androidx.compose.ui.res.stringResource(R.string.update_check))
        }
        // 自動檢查的間隔：啟動 App 時依這個間隔檢查，有新版才跳提示。
        val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
        var intervalHours by remember { mutableStateOf(prefs.getInt(KEY_INTERVAL_HOURS, DEFAULT_INTERVAL_HOURS)) }
        Text(androidx.compose.ui.res.stringResource(R.string.update_interval_title), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            INTERVAL_OPTIONS.forEach { (hours, label) ->
                FilterChip(
                    selected = intervalHours == hours,
                    onClick = {
                        intervalHours = hours
                        prefs.edit().putInt(KEY_INTERVAL_HOURS, hours).apply()
                    },
                    label = { Text(androidx.compose.ui.res.stringResource(label)) },
                )
            }
        }
        Text(
            androidx.compose.ui.res.stringResource(R.string.update_interval_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (controller.hasNew()) {
            if (!controller.downloaded) {
                Button(enabled = !controller.busy, onClick = { controller.download(autoInstall = false) }) {
                    Text(androidx.compose.ui.res.stringResource(R.string.update_download))
                }
            } else {
                Button(onClick = { controller.install() }) {
                    Text(androidx.compose.ui.res.stringResource(R.string.update_install))
                }
            }
        }
        controller.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
        controller.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

private const val PREFS = "app_update"
private const val KEY_LAST_CHECK = "last_check"

/** 啟動時依設定的間隔自動查（預設每天）；沒有新版完全不打擾，有新版才跳出提示。 */
@Composable
fun AutoUpdatePrompt() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { UpdateController(context.applicationContext, scope) }
    var found by remember { mutableStateOf<RemoteUpdate?>(null) }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val hours = prefs.getInt(KEY_INTERVAL_HOURS, DEFAULT_INTERVAL_HOURS)
        if (hours >= 0 && (hours == 0 || now - prefs.getLong(KEY_LAST_CHECK, 0L) >= hours * 60 * 60 * 1000L)) {
            prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
            controller.check(silent = true) { found = it }
        }
    }
    found?.let { info ->
        AlertDialog(
            onDismissRequest = { found = null },
            title = { Text(androidx.compose.ui.res.stringResource(R.string.update_prompt_title)) },
            text = {
                Text(androidx.compose.ui.res.stringResource(R.string.update_prompt_message, info.versionName, BuildConfig.VERSION_NAME))
            },
            confirmButton = {
                TextButton(onClick = {
                    found = null
                    controller.download(autoInstall = true)
                }) { Text(androidx.compose.ui.res.stringResource(R.string.update_prompt_action)) }
            },
            dismissButton = {
                TextButton(onClick = { found = null }) {
                    Text(androidx.compose.ui.res.stringResource(R.string.update_prompt_later))
                }
            },
        )
    }
    if (controller.downloading) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(androidx.compose.ui.res.stringResource(R.string.update_downloading)) },
            text = {
                val p = controller.progress
                if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {},
        )
    }
    // 自動流程出錯（簽章不同、權限等）時才說明；成功安裝會交給系統畫面。
    val error = controller.message?.takeIf { !controller.busy && found == null && it.isNotBlank() && !controller.downloaded }
    var dismissedError by remember { mutableStateOf<String?>(null) }
    if (error != null && error != dismissedError && controller.latest != null && controller.hasNew()) {
        AlertDialog(
            onDismissRequest = { dismissedError = error },
            text = { Text(error) },
            confirmButton = { TextButton(onClick = { dismissedError = error }) { Text(androidx.compose.ui.res.stringResource(android.R.string.ok)) } },
        )
    }
}
