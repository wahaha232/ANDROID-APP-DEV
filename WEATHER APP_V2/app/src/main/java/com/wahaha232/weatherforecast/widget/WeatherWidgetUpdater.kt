// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/widget/WeatherWidgetUpdater.kt
package com.wahaha232.weatherforecast.widget

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import com.wahaha232.weatherforecast.WeatherApplication
import com.wahaha232.weatherforecast.domain.model.City
import com.wahaha232.weatherforecast.domain.model.WeatherForecast
import com.wahaha232.weatherforecast.domain.util.Resource
import kotlinx.serialization.encodeToString

/** Preferences key，儲存序列化後的 [WeatherWidgetData] JSON 字串。 */
val WIDGET_DATA_KEY = stringPreferencesKey("weather_widget_data_json")

/**
 * 所有小工具實例目前都顯示「App 內最後檢視的城市」這份共用快照（未實作每個 widget 各自選城市的
 * Configuration Activity），因此只需要一份全域狀態，更新時廣播給所有已放置的 widget instance。
 */
object WeatherWidgetUpdater {

    /** App 主畫面成功取得天氣後呼叫，讓桌面小工具與 App 保持同步。 */
    suspend fun pushForecast(context: Context, forecast: WeatherForecast) {
        updateAllWidgets(context, forecast.toWidgetData())
    }

    /** 小工具上的手動更新按鈕呼叫：重新抓取上次顯示的城市天氣後更新所有 widget instance。 */
    suspend fun refresh(context: Context, city: City) {
        val container = (context.applicationContext as WeatherApplication).container
        when (val result = container.getWeatherForecastUseCase(city)) {
            is Resource.Success -> updateAllWidgets(context, result.data.toWidgetData())
            else -> Unit // 靜默失敗，畫面上維持舊資料，避免小工具因單次網路錯誤而清空內容
        }
    }

    private suspend fun updateAllWidgets(context: Context, data: WeatherWidgetData) {
        val manager = GlanceAppWidgetManager(context)
        val glanceIds = manager.getGlanceIds(WeatherGlanceWidget::class.java)
        val encoded = widgetJson.encodeToString(data)

        glanceIds.forEach { glanceId ->
            updateAppWidgetState(context, glanceId) { prefs ->
                prefs[WIDGET_DATA_KEY] = encoded
            }
            WeatherGlanceWidget().update(context, glanceId)
        }
    }
}
