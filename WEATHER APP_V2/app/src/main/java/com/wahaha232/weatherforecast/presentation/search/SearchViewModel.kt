// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/presentation/search/SearchViewModel.kt
package com.wahaha232.weatherforecast.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wahaha232.weatherforecast.domain.model.City
import com.wahaha232.weatherforecast.domain.usecase.GetFavoriteCitiesUseCase
import com.wahaha232.weatherforecast.domain.usecase.SearchCitiesUseCase
import com.wahaha232.weatherforecast.domain.usecase.ToggleFavoriteCityUseCase
import com.wahaha232.weatherforecast.domain.util.Resource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 城市搜尋 ViewModel：以 300ms debounce 過濾使用者快速輸入，避免每個字元都觸發一次 API 請求。
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val searchCitiesUseCase: SearchCitiesUseCase,
    private val toggleFavoriteCityUseCase: ToggleFavoriteCityUseCase,
    getFavoriteCitiesUseCase: GetFavoriteCitiesUseCase
) : ViewModel() {

    private val queryFlow = MutableStateFlow("")
    private val isSearchingFlow = MutableStateFlow(false)
    private val resultsFlow = MutableStateFlow<List<City>>(emptyList())
    private val errorFlow = MutableStateFlow<String?>(null)

    private val favoriteCitiesFlow: StateFlow<List<City>> = getFavoriteCitiesUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val uiState: StateFlow<SearchUiState> = combine(
        queryFlow, isSearchingFlow, resultsFlow, favoriteCitiesFlow, errorFlow
    ) { query, isSearching, results, favorites, error ->
        val favoriteIds = favorites.map { it.id }.toSet()
        SearchUiState(
            query = query,
            isSearching = isSearching,
            results = results.map { it.copy(isFavorite = favoriteIds.contains(it.id)) },
            favoriteCities = favorites,
            errorMessage = error
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SearchUiState())

    init {
        queryFlow
            .debounce(300)
            .distinctUntilChanged()
            .onEach { isSearchingFlow.value = it.trim().length >= 2 }
            .flatMapLatest { query ->
                if (query.trim().length < 2) {
                    flowOf(Resource.Success(emptyList()))
                } else {
                    flow { emit(searchCitiesUseCase(query)) }
                }
            }
            .catch { emit(Resource.Error(it.message ?: "搜尋時發生未知錯誤")) }
            .onEach { result ->
                isSearchingFlow.value = false
                when (result) {
                    is Resource.Success -> {
                        resultsFlow.value = result.data
                        errorFlow.value = null
                    }
                    is Resource.Error -> {
                        resultsFlow.value = emptyList()
                        errorFlow.value = result.message
                    }
                    Resource.Loading -> Unit
                }
            }
            .launchIn(viewModelScope)
    }

    fun onIntent(intent: SearchIntent) {
        when (intent) {
            is SearchIntent.QueryChanged -> queryFlow.value = intent.query
            is SearchIntent.ToggleFavorite -> viewModelScope.launch {
                toggleFavoriteCityUseCase(intent.city)
            }
            is SearchIntent.SelectCity -> Unit // 由畫面呼叫端（NavHost）處理導航與切換城市
        }
    }
}
