package com.startinsnow.healthtracker

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.startinsnow.healthtracker.security.AppLock
import com.startinsnow.healthtracker.ui.HealthTrackerRoot
import com.startinsnow.healthtracker.ui.LockScreen
import com.startinsnow.healthtracker.ui.theme.HealthTrackerTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : FragmentActivity() {
    private var lockEnabled = false
    private var locked by mutableStateOf(false)
    private var prompting = false
    private var leftAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as HealthTrackerApp
        // 冷啟動先讀一次設定，第一個畫面就上鎖，不會先閃出內容
        lockEnabled = runBlocking { app.settings.settings.first().appLock }
        locked = lockEnabled
        applySecure()
        lifecycleScope.launch {
            app.settings.settings.collect {
                lockEnabled = it.appLock
                if (!it.appLock) locked = false
                applySecure()
            }
        }
        setContent {
            HealthTrackerTheme {
                if (locked) LockScreen(onUnlock = { requestUnlock() })
                else HealthTrackerRoot(app.records, app.settings, app.cloud)
            }
        }
    }

    /** 開啟鎖時隱藏最近工作畫面的縮圖與截圖。 */
    private fun applySecure() {
        if (lockEnabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onStop() {
        super.onStop()
        leftAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        // 離開超過 2 分鐘才重新上鎖（拍照、選照片的短暫離開不打擾）
        if (lockEnabled && !locked && leftAt != 0L && SystemClock.elapsedRealtime() - leftAt > GRACE_MS) locked = true
    }

    override fun onResume() {
        super.onResume()
        if (locked) requestUnlock()
    }

    private fun requestUnlock() {
        if (prompting) return
        if (!AppLock.available(this)) { // 手機已取消螢幕鎖定：不把使用者鎖在外面
            locked = false
            return
        }
        prompting = true
        AppLock.prompt(this, "解鎖血糖記錄") { ok ->
            prompting = false
            if (ok) locked = false
        }
    }

    private companion object {
        const val GRACE_MS = 2 * 60 * 1000L
    }
}
