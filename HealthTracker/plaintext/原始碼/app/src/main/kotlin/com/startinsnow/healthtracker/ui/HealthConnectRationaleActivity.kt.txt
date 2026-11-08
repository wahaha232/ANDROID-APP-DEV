package com.startinsnow.healthtracker.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Health Connect 授權流程要求的「資料使用說明」頁。 */
class HealthConnectRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Column(Modifier.padding(24.dp)) {
                    Text("這支 App 只會讀取體重與體脂率，匯入到這支 App 的本機資料庫。資料不會傳給第三方；只有您登入並按「立即同步」才會上傳到您自己的雲端。")
                    Button(onClick = { finish() }, modifier = Modifier.padding(top = 16.dp)) { Text("知道了") }
                }
            }
        }
    }
}
