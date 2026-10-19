package com.startinsnow.healthtracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.startinsnow.healthtracker.ui.HealthTrackerRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as HealthTrackerApp
        setContent {
            MaterialTheme {
                HealthTrackerRoot(app.records, app.settings)
            }
        }
    }
}
