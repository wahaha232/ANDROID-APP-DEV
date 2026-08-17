// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/data/local/DataStoreExt.kt
package com.wahaha232.weatherforecast.data.local

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

/** App 層級唯一的 DataStore<Preferences> 實例（by 委派確保單例，避免多個 DataStore 檔案衝突） */
val Context.settingsDataStore by preferencesDataStore(name = "weather_app_settings")
