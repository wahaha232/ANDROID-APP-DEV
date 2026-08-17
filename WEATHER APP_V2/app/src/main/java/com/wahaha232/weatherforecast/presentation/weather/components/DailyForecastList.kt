// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/presentation/weather/components/DailyForecastList.kt
package com.wahaha232.weatherforecast.presentation.weather.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wahaha232.weatherforecast.domain.model.AppSettings
import com.wahaha232.weatherforecast.domain.model.DailyForecastItem
import com.wahaha232.weatherforecast.domain.model.UnitFormatter

/**
 * 「7 天天氣預測趨勢」區塊標題，放在 WeatherScreen 根 LazyColumn 的 item{} 中。
 */
@Composable
fun DailyForecastSectionHeader(modifier: Modifier = Modifier) {
    Text(
        text = "7 天天氣預測趨勢",
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.fillMaxWidth().padding(top = 8.dp)
    )
}

/**
 * 每日預報單列：日期、圖示、降雨機率、最高/最低溫。
 * 由呼叫端（WeatherScreen）以 `items(forecast.daily) { DailyForecastRow(it, ...) }` 放進根 LazyColumn，
 * 避免在畫面中出現「LazyColumn 巢狀 LazyColumn」造成的高度量測例外。
 */
@Composable
fun DailyForecastRow(
    item: DailyForecastItem,
    settings: AppSettings,
    isToday: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = if (isToday) "今天" else item.dateIso,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(56.dp)
        )
        Icon(
            imageVector = item.conditionType.toIcon(),
            contentDescription = item.conditionType.displayNameZh,
            modifier = Modifier.height(24.dp)
        )
        Text(
            text = "☔ ${item.precipitationProbabilityMax}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(64.dp)
        )
        Text(
            text = "↓${UnitFormatter.formatTemperature(item.temperatureMin, settings.temperatureUnit)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "↑${UnitFormatter.formatTemperature(item.temperatureMax, settings.temperatureUnit)}",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
