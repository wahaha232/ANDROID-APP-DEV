// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/di/LocalAppContainer.kt
package com.wahaha232.weatherforecast.di

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 讓整個 Compose Tree 都能透過 `LocalAppContainer.current` 取得 AppContainer，
 * 藉此在各個 Composable 內建立 ViewModel Factory，避免 Hilt 也能維持可測試性。
 */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer 尚未提供，請確認 MainActivity 已透過 CompositionLocalProvider 注入")
}
