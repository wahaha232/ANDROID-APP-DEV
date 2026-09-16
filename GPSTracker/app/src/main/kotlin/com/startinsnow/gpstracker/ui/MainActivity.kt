package com.startinsnow.gpstracker.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.startinsnow.gpstracker.GpsTrackerApplication
import com.startinsnow.gpstracker.core.model.TrackStatus
import com.startinsnow.gpstracker.service.TrackingForegroundService
import com.startinsnow.gpstracker.ui.nav.GpsTrackerNavHost
import com.startinsnow.gpstracker.ui.theme.GpsTrackerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Crash Recovery（規格 57/98）：App 重啟時檢查是否有未正常結束的 Track，若有就接續記錄。
        val app = application as GpsTrackerApplication
        lifecycleScope.launch {
            val recoverable = app.trackRepository.findRecoverableTrack()
            if (recoverable != null && recoverable.status != TrackStatus.FINISHED) {
                TrackingForegroundService.startResume(this@MainActivity, recoverable.trackId)
            }
        }

        setContent {
            GpsTrackerTheme {
                GpsTrackerNavHost()
            }
        }
    }
}
