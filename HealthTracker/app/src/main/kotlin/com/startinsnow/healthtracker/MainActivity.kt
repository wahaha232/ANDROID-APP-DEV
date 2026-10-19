package com.startinsnow.healthtracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.startinsnow.healthtracker.ui.theme.HealthTrackerTheme
import com.startinsnow.healthtracker.ui.HealthTrackerRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as HealthTrackerApp
        setContent {
            HealthTrackerTheme {
                HealthTrackerRoot(app.records, app.settings, app.cloud)
            }
        }
    }
}
