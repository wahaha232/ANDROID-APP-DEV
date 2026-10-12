package com.startinsnow.gpstracker.ui.health

import android.os.Bundle
import com.startinsnow.gpstracker.R
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Health Connect 權限說明頁。
 *
 * Android 14 以上的 Health Connect 會用這個畫面（透過 ACTION_SHOW_PERMISSIONS_RATIONALE 與
 * VIEW_PERMISSION_USAGE）向使用者說明 App 為何需要健康資料；缺少它時，某些裝置不會正常顯示
 * 授權流程。內容僅說明用途，不做任何資料存取。
 */
class HealthConnectRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            text = getString(R.string.hc_rationale_body)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(text)
            }
        )
    }
}
