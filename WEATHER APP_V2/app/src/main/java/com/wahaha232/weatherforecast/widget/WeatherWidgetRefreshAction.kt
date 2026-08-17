// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/widget/WeatherWidgetRefreshAction.kt
package com.wahaha232.weatherforecast.widget

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.wahaha232.weatherforecast.domain.model.City
import kotlinx.serialization.decodeFromString

/**
 * 小工具右上角「手動更新」按鈕的行為：讀出目前小工具快照裡記錄的城市座標，
 * 重新呼叫 Repository 抓取最新天氣後更新所有 widget instance。
 */
class WeatherWidgetRefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: androidx.glance.action.ActionParameters) {
        val prefs: Preferences = getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)
        val json = prefs[WIDGET_DATA_KEY] ?: return
        val data = runCatching { widgetJson.decodeFromString<WeatherWidgetData>(json) }.getOrNull() ?: return

        val city = City(
            id = data.cityId,
            name = data.cityName,
            admin1 = null,
            country = null,
            countryCode = null,
            latitude = data.latitude,
            longitude = data.longitude,
            timezone = null
        )

        WeatherWidgetUpdater.refresh(context, city)
    }
}
