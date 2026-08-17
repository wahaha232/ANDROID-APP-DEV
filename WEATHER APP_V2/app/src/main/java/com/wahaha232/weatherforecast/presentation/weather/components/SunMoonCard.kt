// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/presentation/weather/components/SunMoonCard.kt
package com.wahaha232.weatherforecast.presentation.weather.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wahaha232.weatherforecast.domain.model.DailyForecastItem
import com.wahaha232.weatherforecast.domain.model.WeatherAnalytics

/**
 * 日出/日落與月相卡片。
 */
@Composable
fun SunMoonCard(
    today: DailyForecastItem?,
    nowEpochMillis: Long,
    modifier: Modifier = Modifier
) {
    val moon = WeatherAnalytics.getMoonPhase(nowEpochMillis)
    val sunrise = today?.sunriseIso?.substringAfter("T") ?: "--:--"
    val sunset = today?.sunsetIso?.substringAfter("T") ?: "--:--"

    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(text = "日月", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(text = "日出", style = MaterialTheme.typography.labelSmall)
                Text(text = sunrise, style = MaterialTheme.typography.titleMedium)
            }
            Column {
                Text(text = "${moon.icon} ${moon.nameZh}", style = MaterialTheme.typography.titleMedium)
            }
            Column {
                Text(text = "日落", style = MaterialTheme.typography.labelSmall)
                Text(text = sunset, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
