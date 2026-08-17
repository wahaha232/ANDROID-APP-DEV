// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/presentation/weather/components/CurrentWeatherCard.kt
package com.wahaha232.weatherforecast.presentation.weather.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.wahaha232.weatherforecast.domain.model.AppSettings
import com.wahaha232.weatherforecast.domain.model.City
import com.wahaha232.weatherforecast.domain.model.CurrentWeather
import com.wahaha232.weatherforecast.domain.model.DailyForecastItem
import com.wahaha232.weatherforecast.domain.model.UnitFormatter

/**
 * 目前天氣狀況主卡片：城市名稱、天氣圖示、即時氣溫、體感溫度、當日高低溫。
 */
@Composable
fun CurrentWeatherCard(
    city: City,
    current: CurrentWeather,
    today: DailyForecastItem?,
    settings: AppSettings,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 12.dp)
    ) {
        Text(
            text = city.fullDisplayName,
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = current.conditionType.toIcon(current.isDay),
                contentDescription = current.conditionType.displayNameZh,
                tint = Color(current.conditionType.accentColorArgb()),
                modifier = Modifier.size(64.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = UnitFormatter.formatTemperature(current.temperature, settings.temperatureUnit),
                    style = MaterialTheme.typography.displayLarge
                )
                Text(
                    text = current.conditionType.displayNameZh,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "體感 ${UnitFormatter.formatTemperature(current.apparentTemperature, settings.temperatureUnit)}" +
                if (today != null) {
                    "　↑${UnitFormatter.formatTemperature(today.temperatureMax, settings.temperatureUnit)} " +
                        "↓${UnitFormatter.formatTemperature(today.temperatureMin, settings.temperatureUnit)}"
                } else "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
