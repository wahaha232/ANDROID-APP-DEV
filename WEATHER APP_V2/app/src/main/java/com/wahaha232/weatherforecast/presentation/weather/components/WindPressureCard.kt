// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/presentation/weather/components/WindPressureCard.kt
package com.wahaha232.weatherforecast.presentation.weather.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wahaha232.weatherforecast.domain.model.AppSettings
import com.wahaha232.weatherforecast.domain.model.CurrentWeather
import com.wahaha232.weatherforecast.domain.model.UnitFormatter
import com.wahaha232.weatherforecast.domain.model.WeatherAnalytics

/**
 * 風速/風向與氣壓卡片。
 */
@Composable
fun WindPressureCard(
    current: CurrentWeather,
    settings: AppSettings,
    modifier: Modifier = Modifier
) {
    val beaufort = WeatherAnalytics.getBeaufortScale(current.windSpeedKmh)
    val windDirLabel = WeatherAnalytics.getWindDirectionLabel(current.windDirectionDeg)
    val (windValue, windUnit) = UnitFormatter.formatWindSpeed(current.windSpeedKmh, settings.windSpeedUnit)
    val (gustValue, gustUnit) = UnitFormatter.formatWindSpeed(current.windGustsKmh, settings.windSpeedUnit)
    val (pressureValue, pressureUnit) = UnitFormatter.formatPressure(current.pressureHpa, settings.pressureUnit)

    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(text = "風 / 壓強", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Icon(Icons.Filled.Air, contentDescription = null, modifier = Modifier.height(20.dp))
                Text(text = "風速與風向", style = MaterialTheme.typography.labelSmall)
                Text(text = windDirLabel, style = MaterialTheme.typography.bodyMedium)
                Text(text = "$windValue $windUnit", style = MaterialTheme.typography.titleMedium)
                Text(text = beaufort.descriptionZh, style = MaterialTheme.typography.labelSmall)
                Text(
                    text = "陣風最高達：$gustValue $gustUnit",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column {
                Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.height(20.dp))
                Text(text = "壓強", style = MaterialTheme.typography.labelSmall)
                Text(text = "$pressureValue", style = MaterialTheme.typography.titleMedium)
                Text(text = pressureUnit, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
