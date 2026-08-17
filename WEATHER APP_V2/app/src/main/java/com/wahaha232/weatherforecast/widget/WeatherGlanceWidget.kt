// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/widget/WeatherGlanceWidget.kt
package com.wahaha232.weatherforecast.widget

import androidx.compose.runtime.Composable
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wahaha232.weatherforecast.R
import kotlinx.serialization.decodeFromString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val WidgetOnSurface = Color(0xFFFFFFFF)
private val WidgetOnSurfaceVariant = Color(0xFFB6C2D9)
private val WidgetDividerColor = Color(0x33FFFFFF)

private val SIZE_SMALL = androidx.compose.ui.unit.DpSize(120.dp, 90.dp)
private val SIZE_MEDIUM = androidx.compose.ui.unit.DpSize(220.dp, 110.dp)
private val SIZE_LARGE = androidx.compose.ui.unit.DpSize(280.dp, 220.dp)

/**
 * 天氣桌面小工具。支援三種可調整大小的版面（Responsive SizeMode）：
 * - Small：僅顯示溫度與圖示
 * - Medium：加上城市名稱、時間、日期
 * - Large：完整版面，含 4 天預報列（對應使用者提供的截圖樣式）
 */
class WeatherGlanceWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override val sizeMode = SizeMode.Responsive(setOf(SIZE_SMALL, SIZE_MEDIUM, SIZE_LARGE))

    override suspend fun provideGlance(context: android.content.Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            val json = prefs[WIDGET_DATA_KEY]
            val data = json?.let { runCatching { widgetJson.decodeFromString<WeatherWidgetData>(it) }.getOrNull() }

            GlanceTheme {
                WeatherWidgetContent(data = data)
            }
        }
    }
}

@Composable
private fun WeatherWidgetContent(data: WeatherWidgetData?) {
    val size = androidx.glance.LocalSize.current
    val compact = size.width < SIZE_MEDIUM.width
    val showDaily = size.height >= SIZE_LARGE.height && data != null && data.daily.isNotEmpty()

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ImageProvider(R.drawable.widget_background_gradient))
            .cornerRadius(20.dp)
            .padding(14.dp)
    ) {
        if (data == null) {
            Text(
                text = "尚無天氣資料，開啟 App 或點擊重新整理",
                style = TextStyle(color = ColorProvider(WidgetOnSurfaceVariant), fontSize = 12.sp)
            )
            return@Column
        }

        HeaderRow(data)
        Spacer(modifier = GlanceModifier.height(6.dp))
        CurrentWeatherRow(data, compact = compact)

        if (showDaily) {
            Spacer(modifier = GlanceModifier.height(10.dp))
            Spacer(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(WidgetDividerColor)
            )
            Spacer(modifier = GlanceModifier.height(10.dp))
            DailyForecastRow(data)
        }
    }
}

@Composable
private fun HeaderRow(data: WeatherWidgetData) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically
    ) {
        Text(
            text = data.cityName,
            style = TextStyle(
                color = ColorProvider(WidgetOnSurface),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            ),
            modifier = GlanceModifier.defaultWeight()
        )
        Image(
            provider = ImageProvider(R.drawable.ic_widget_refresh),
            contentDescription = "手動更新",
            modifier = GlanceModifier
                .size(22.dp)
                .clickable(actionRunCallback<WeatherWidgetRefreshAction>())
        )
    }
}

@Composable
private fun CurrentWeatherRow(data: WeatherWidgetData, compact: Boolean) {
    val now = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(data.lastUpdatedEpochMillis))
    val date = SimpleDateFormat("M/d EEE", Locale.TAIWAN).format(Date(data.lastUpdatedEpochMillis))

    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically
    ) {
        Text(
            text = "${data.currentTemp}°",
            style = TextStyle(
                color = ColorProvider(WidgetOnSurface),
                fontSize = if (compact) 34.sp else 52.sp,
                fontWeight = FontWeight.Bold
            )
        )
        Spacer(modifier = GlanceModifier.width(10.dp))
        Image(
            provider = ImageProvider(data.conditionType.toWidgetIconRes(data.isDay)),
            contentDescription = data.conditionLabel,
            modifier = GlanceModifier.size(if (compact) 32.dp else 48.dp)
        )
        if (!compact) {
            Spacer(modifier = GlanceModifier.defaultWeight())
            Column(horizontalAlignment = Alignment.Horizontal.End) {
                Text(
                    text = now,
                    style = TextStyle(color = ColorProvider(WidgetOnSurface), fontSize = 36.sp, fontWeight = FontWeight.Medium)
                )
                Text(text = date, style = TextStyle(color = ColorProvider(WidgetOnSurfaceVariant), fontSize = 12.sp))
            }
        }
    }
    Spacer(modifier = GlanceModifier.height(4.dp))
    Text(
        text = "${data.conditionLabel}  ${data.tempMax}°/${data.tempMin}°",
        style = TextStyle(color = ColorProvider(WidgetOnSurfaceVariant), fontSize = 13.sp)
    )
}

@Composable
private fun DailyForecastRow(data: WeatherWidgetData) {
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        data.daily.forEach { day ->
            Column(
                modifier = GlanceModifier.defaultWeight(),
                horizontalAlignment = Alignment.Horizontal.CenterHorizontally
            ) {
                Text(text = day.weekdayLabel, style = TextStyle(color = ColorProvider(WidgetOnSurfaceVariant), fontSize = 12.sp))
                Spacer(modifier = GlanceModifier.height(4.dp))
                Image(
                    provider = ImageProvider(day.conditionType.toWidgetIconRes(true)),
                    contentDescription = null,
                    modifier = GlanceModifier.size(22.dp)
                )
                Spacer(modifier = GlanceModifier.height(4.dp))
                Text(
                    text = "${day.tempMax}°/${day.tempMin}°",
                    style = TextStyle(color = ColorProvider(WidgetOnSurface), fontSize = 11.sp)
                )
            }
        }
    }
}
