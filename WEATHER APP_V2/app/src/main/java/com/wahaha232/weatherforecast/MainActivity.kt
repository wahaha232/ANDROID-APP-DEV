// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/MainActivity.kt
package com.wahaha232.weatherforecast

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.wahaha232.weatherforecast.di.LocalAppContainer
import com.wahaha232.weatherforecast.presentation.navigation.WeatherNavHost
import com.wahaha232.weatherforecast.presentation.theme.WeatherForecastTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as WeatherApplication).container

        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                WeatherForecastTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        WeatherNavHost(container = container)
                    }
                }
            }
        }
    }
}
