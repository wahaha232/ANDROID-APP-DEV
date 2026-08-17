// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/presentation/weather/components/PhotographyCard.kt
package com.wahaha232.weatherforecast.presentation.weather.components

import androidx.compose.foundation.layout.Column
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
 * 攝影建議卡片：依當日日出/日落時間推算黃金時段與藍色時段。
 */
@Composable
fun PhotographyCard(
    today: DailyForecastItem?,
    modifier: Modifier = Modifier
) {
    val times = WeatherAnalytics.getPhotographyTimes(today?.sunriseIso, today?.sunsetIso)

    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(text = "攝影", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))

        Text(text = "黃金時段", style = MaterialTheme.typography.bodyMedium)
        Text(
            text = "當天空從紅色變為黃色時，天空將具有黃金色調，風景攝影的理想選擇。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = "${times.goldenMorning}　/　${times.goldenEvening}", style = MaterialTheme.typography.bodyMedium)

        Spacer(Modifier.height(12.dp))

        Text(text = "藍色時段", style = MaterialTheme.typography.bodyMedium)
        Text(
            text = "天空將有深藍色調和冷飽和色，城市攝影的理想選擇。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = "${times.blueMorning}　/　${times.blueEvening}", style = MaterialTheme.typography.bodyMedium)
    }
}
