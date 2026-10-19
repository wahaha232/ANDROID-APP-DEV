package com.startinsnow.healthtracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.startinsnow.healthtracker.cloud.CloudException
import com.startinsnow.healthtracker.cloud.CloudSync
import com.startinsnow.healthtracker.cloud.Session
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CloudSection(cloud: CloudSync) {
    val engine = cloud.engine
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("雲端同步", style = MaterialTheme.typography.titleLarge)
        if (engine == null) {
            Text(
                "尚未設定雲端。請在 local.properties 加入 supabase.url 與 supabase.anonKey 後重新建置。",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }

        val scope = rememberCoroutineScope()
        var session by remember { mutableStateOf<Session?>(null) }
        var lastSync by remember { mutableStateOf<Long?>(null) }
        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        var loaded by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            session = engine.currentSession()
            lastSync = cloud.sessions.lastSyncAt()
            loaded = true
        }

        val current = session
        if (!loaded) {
            Text("讀取中…")
        } else if (current == null) {
            OutlinedTextField(
                email, { email = it }, label = { Text("Email") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                password, { password = it }, label = { Text("密碼") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(enabled = !busy && email.isNotBlank() && password.isNotEmpty(), onClick = {
                busy = true
                message = null
                scope.launch {
                    try {
                        engine.signIn(email, password)
                        session = engine.currentSession()
                        password = ""
                        message = "登入成功"
                    } catch (e: CloudException) {
                        message = e.message
                    } catch (e: Exception) {
                        message = "登入失敗：${e.message}"
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "登入中…" else "登入") }
        } else {
            Text("已登入：${current.email ?: current.userId}")
            Text(
                "上次同步：" + (lastSync?.let { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.TAIWAN).format(Date(it)) } ?: "尚未同步"),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = {
                    busy = true
                    message = null
                    scope.launch {
                        val r = engine.sync()
                        message = if (r.error != null) r.error else "同步完成：下載 ${r.pulled} 筆、上傳 ${r.pushed} 筆"
                        lastSync = cloud.sessions.lastSyncAt()
                        session = engine.currentSession() // 登入失效時憑證會被清掉，畫面回到登入表單
                        busy = false
                    }
                }) { Text(if (busy) "同步中…" else "立即同步") }
                OutlinedButton(enabled = !busy, onClick = {
                    scope.launch {
                        engine.signOut()
                        session = null
                        message = "已登出（本機資料保留）"
                    }
                }) { Text("登出") }
            }
        }
        message?.let { Text(it, color = if (it.startsWith("同步完成") || it == "登入成功" || it.startsWith("已登出")) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error) }
    }
}
