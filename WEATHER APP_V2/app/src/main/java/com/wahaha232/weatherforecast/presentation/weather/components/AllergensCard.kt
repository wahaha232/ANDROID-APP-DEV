// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/presentation/weather/components/AllergensCard.kt
package com.wahaha232.weatherforecast.presentation.weather.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wahaha232.weatherforecast.domain.model.AirQuality
import com.wahaha232.weatherforecast.domain.model.CurrentWeather
import com.wahaha232.weatherforecast.domain.model.WeatherAnalytics

/**
 * 過敏原推估卡片：灰塵/皮屑、樹木花粉、青草花粉。
 */
@Composable
fun AllergensCard(
    current: CurrentWeather,
    airQuality: AirQuality?,
    modifier: Modifier = Modifier
) {
    val items = WeatherAnalytics.calculateAllergenLevels(current.relativeHumidity, airQuality?.usAqi)

    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(text = "過敏", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        items.forEach { info ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = info.nameZh, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = info.levelZh,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (info.severityLevel) {
                        1 -> MaterialTheme.colorScheme.primary
                        2 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.error
                    }
                )
            }
        }
    }
}
