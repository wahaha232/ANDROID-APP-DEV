// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/domain/util/Resource.kt
package com.wahaha232.weatherforecast.domain.util

/**
 * 統一封裝 Repository -> UseCase -> ViewModel 資料流的結果狀態，
 * 讓每一層都用同一種方式處理成功/失敗/載入中，避免例外散落各處。
 */
sealed class Resource<out T> {
    data class Success<T>(val data: T) : Resource<T>()
    data class Error(val message: String, val cause: Throwable? = null) : Resource<Nothing>()
    data object Loading : Resource<Nothing>()
}
