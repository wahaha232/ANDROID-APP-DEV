// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/presentation/weather/components/AqiCard.kt
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wahaha232.weatherforecast.domain.model.AirQuality
import com.wahaha232.weatherforecast.domain.model.WeatherAnalytics

/**
 * 空氣品質指數卡片（US AQI 標準）：總分 + PM2.5/PM10/CO/NO2/SO2/O3 各項濃度。
 */
@Composable
fun AqiCard(
    airQuality: AirQuality?,
    modifier: Modifier = Modifier
) {
    val category = WeatherAnalytics.getAqiCategory(airQuality?.usAqi)

    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(text = "空氣質量指數 (AQI)", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                text = airQuality?.usAqi?.toString() ?: "--",
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(0.dp))
            Text(
                text = "  ${category.labelZh}",
                style = MaterialTheme.typography.titleMedium
            )
        }
        Text(
            text = category.adviceZh,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            AqiMetric("PM2.5", airQuality?.pm2_5)
            AqiMetric("PM10", airQuality?.pm10)
            AqiMetric("CO", airQuality?.carbonMonoxide)
            AqiMetric("NO2", airQuality?.nitrogenDioxide)
            AqiMetric("SO2", airQuality?.sulphurDioxide)
            AqiMetric("O3", airQuality?.ozone)
        }
    }
}

@Composable
private fun AqiMetric(label: String, value: Double?) {
    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text(
            text = value?.let { "%.0f".format(it) } ?: "--",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(text = label, style = MaterialTheme.typography.labelSmall)
    }
}
