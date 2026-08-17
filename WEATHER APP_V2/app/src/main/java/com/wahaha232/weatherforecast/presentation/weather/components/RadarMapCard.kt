// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/presentation/weather/components/RadarMapCard.kt
package com.wahaha232.weatherforecast.presentation.weather.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.wahaha232.weatherforecast.domain.model.City

/**
 * 雷達地圖示意卡片。
 *
 * Open-Meteo 免費方案不提供逐格降水雷達圖磚，若要顯示真實雷達影像需另外串接
 * 付費雷達圖磚服務（例如 RainViewer、Windy）。此處以裝飾性漸層取代真實雷達資料，
 * 保留卡片版位與城市座標資訊，未來若要換成真實雷達圖只需替換此 Composable 內部實作。
 */
@Composable
fun RadarMapCard(
    city: City,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(text = "雷達地圖", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF2196F3).copy(alpha = 0.5f),
                            Color(0xFF4CAF50).copy(alpha = 0.3f),
                            Color(0xFF0D47A1).copy(alpha = 0.6f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = city.fullDisplayName,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "降水強度示意圖（示範用途，非即時雷達影像）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
