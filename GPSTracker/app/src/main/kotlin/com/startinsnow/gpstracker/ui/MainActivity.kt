package com.startinsnow.gpstracker.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.startinsnow.gpstracker.GpsTrackerApplication
import com.startinsnow.gpstracker.core.model.TrackStatus
import com.startinsnow.gpstracker.service.TrackingStateHolder
import com.startinsnow.gpstracker.ui.nav.GpsTrackerNavHost
import com.startinsnow.gpstracker.ui.theme.GpsTrackerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Crash Recovery（規格 57/98）：App 重啟時檢查是否有未正常結束的 Track。
        // 只「偵測並提示」，不自動續錄 —— 自動續錄會讓每次開啟 App 都產生一筆幽靈記錄，
        // 也會和使用者按下的「開始記錄」互相打架（見 HomeScreen 的接續 / 結束按鈕）。
        val app = application as GpsTrackerApplication
        lifecycleScope.launch {
            val recoverable = runCatching { app.trackRepository.findRecoverableTrack() }.getOrNull()
            if (recoverable != null && recoverable.status != TrackStatus.FINISHED) {
                TrackingStateHolder.setPendingRecovery(
                    TrackingStateHolder.RecoveryCandidate(
                        trackId = recoverable.trackId,
                        startTimeMs = recoverable.startTimeMs,
                        pointCount = recoverable.gpsPointCount
                    )
                )
            }
        }

        setContent {
            GpsTrackerTheme {
                GpsTrackerNavHost()
            }
        }
    }
}
