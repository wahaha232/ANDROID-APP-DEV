// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/widget/WeatherWidgetReceiver.kt
package com.wahaha232.weatherforecast.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * 三個尺寸各自獨立的 AppWidgetProvider 進入點，讓使用者在桌面「新增小工具」選單裡能直接
 * 挑選想要的尺寸（而不是只能新增單一尺寸後再靠長按拖曳調整）。三者都指向同一個
 * [WeatherGlanceWidget]，實際版面則由其內部的 `SizeMode.Responsive` 依可用空間自動切換。
 */
class WeatherWidgetReceiverSmall : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeatherGlanceWidget()
}

class WeatherWidgetReceiverMedium : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeatherGlanceWidget()
}

class WeatherWidgetReceiverLarge : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeatherGlanceWidget()
}
