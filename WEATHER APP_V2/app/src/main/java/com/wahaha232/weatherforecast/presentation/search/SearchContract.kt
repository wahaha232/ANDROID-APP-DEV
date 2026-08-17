// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/presentation/search/SearchContract.kt
package com.wahaha232.weatherforecast.presentation.search

import com.wahaha232.weatherforecast.domain.model.City

/**
 * 城市搜尋畫面的 UI State。query 與 results 分離管理，
 * 讓輸入框可以即時反映使用者輸入，同時搜尋結果只在防抖後才更新。
 */
data class SearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val results: List<City> = emptyList(),
    val favoriteCities: List<City> = emptyList(),
    val errorMessage: String? = null
)

sealed interface SearchIntent {
    data class QueryChanged(val query: String) : SearchIntent
    data class SelectCity(val city: City) : SearchIntent
    data class ToggleFavorite(val city: City) : SearchIntent
}
