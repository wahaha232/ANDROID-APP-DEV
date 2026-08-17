// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/presentation/weather/components/HourlyForecastRow.kt
package com.wahaha232.weatherforecast.presentation.weather.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wahaha232.weatherforecast.domain.model.AppSettings
import com.wahaha232.weatherforecast.domain.model.HourlyForecastItem
import com.wahaha232.weatherforecast.domain.model.UnitFormatter

/**
 * 24 小時逐時天氣預報：水平滑動 LazyRow，每格顯示時間、圖示、溫度、降雨機率。
 */
@Composable
fun HourlyForecastRow(
    hourly: List<HourlyForecastItem>,
    settings: AppSettings,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "24 小時逐時天氣預報",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(hourly, key = { it.timeIso }) { item ->
                HourlyForecastCell(item, settings)
            }
        }
    }
}

@Composable
private fun HourlyForecastCell(item: HourlyForecastItem, settings: AppSettings) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(56.dp)
    ) {
        Text(
            text = item.timeIso.substringAfter("T"),
            style = MaterialTheme.typography.labelSmall
        )
        Spacer(Modifier.height(6.dp))
        Icon(
            imageVector = item.conditionType.toIcon(),
            contentDescription = item.conditionType.displayNameZh,
            modifier = Modifier.height(28.dp)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = UnitFormatter.formatTemperature(item.temperature, settings.temperatureUnit),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${item.precipitationProbability}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
