package com.startinsnow.healthtracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.startinsnow.healthtracker.BuildConfig
import com.startinsnow.healthtracker.cloud.CloudSync
import com.startinsnow.healthtracker.data.prefs.Sex
import com.startinsnow.healthtracker.data.prefs.UserSettings
import com.startinsnow.healthtracker.data.prefs.UserSettingsRepository
import com.startinsnow.healthtracker.data.repository.HealthRecordRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(repo: UserSettingsRepository, records: HealthRecordRepository, cloud: CloudSync) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var sex by remember { mutableStateOf(Sex.UNSPECIFIED) }
    var birthYear by remember { mutableStateOf("") }
    var height by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var group by rememberSaveable { mutableIntStateOf(0) }

    // 只在進入畫面時載入一次，之後以輸入框內容為準。
    LaunchedEffect(Unit) {
        val s = repo.settings.first()
        name = s.displayName
        sex = s.sex
        birthYear = s.birthYear?.toString().orEmpty()
        height = s.heightCm?.toString().orEmpty()
        target = s.targetWeightKg?.toString().orEmpty()
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 設定分組：一次只顯示一組，常用的個人資料與雲端同步不必在一長串裡找
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("個人資料", "匯入資料", "雲端同步", "備份與隱私", "關於").forEachIndexed { i, name ->
                FilterChip(selected = group == i, onClick = { group = i }, label = { Text(name) })
            }
        }
        if (group == 0) {
        Text("個人設定", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(name, { name = it }, label = { Text("顯示名稱") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Sex.UNSPECIFIED to "不指定", Sex.MALE to "男", Sex.FEMALE to "女").forEach { (s, label) ->
                FilterChip(selected = sex == s, onClick = { sex = s }, label = { Text(label) })
            }
        }
        OutlinedTextField(birthYear, { birthYear = it }, label = { Text("出生年（西元）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(height, { height = it }, label = { Text("身高（cm）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(target, { target = it }, label = { Text("目標體重（kg）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            val by = birthYear.toIntOrNull()
            val h = height.toDoubleOrNull()
            val t = target.toDoubleOrNull()
            message = when {
                birthYear.isNotBlank() && (by == null || by !in 1900..2100) -> "出生年請填 1900–2100"
                height.isNotBlank() && (h == null || h !in 100.0..250.0) -> "身高請填 100–250 cm"
                target.isNotBlank() && (t == null || t !in 20.0..300.0) -> "目標體重請填 20–300 kg"
                else -> {
                    scope.launch { repo.save(UserSettings(name.trim(), sex, by, h, t)) }
                    "已儲存"
                }
            }
        }) { Text("儲存") }
        message?.let { Text(it) }
        }
        if (group == 1) ImportSection(records)
        if (group == 2) CloudSection(cloud)
        if (group == 3) BackupSection(records)
        if (group == 4) {
            Text("關於", style = MaterialTheme.typography.titleLarge)
            Text("血糖記錄　版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）", style = MaterialTheme.typography.bodyMedium)
            Text("資料預設只存在這支手機；登入並同步後才會上傳到您自己的雲端。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
