// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/presentation/search/SearchScreen.kt
package com.wahaha232.weatherforecast.presentation.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wahaha232.weatherforecast.domain.model.City

/**
 * 城市搜尋畫面：即時輸入防抖搜尋 + 收藏最愛城市清單。
 * uiState 透過 collectAsStateWithLifecycle 收集，畫面進入背景時自動停止收集，避免不必要的重組與資源浪費。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onBack: () -> Unit,
    onCitySelected: (City) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = uiState.query,
                        onValueChange = { viewModel.onIntent(SearchIntent.QueryChanged(it)) },
                        placeholder = { Text("輸入城市名稱（例如：台北、東京、London...）") },
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                uiState.isSearching -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                uiState.errorMessage != null -> {
                    Text(
                        text = uiState.errorMessage ?: "",
                        modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                uiState.query.trim().length < 2 -> {
                    SearchSectionHeader("我的最愛城市")
                    LazyColumn {
                        items(uiState.favoriteCities, key = { it.id }) { city ->
                            CityResultRow(
                                city = city,
                                onClick = { onCitySelected(city) },
                                onToggleFavorite = { viewModel.onIntent(SearchIntent.ToggleFavorite(city)) }
                            )
                        }
                    }
                }
                uiState.results.isEmpty() -> {
                    Text(
                        text = "找不到符合條件的城市，請嘗試輸入其他拼音或中英文城市名稱",
                        modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    SearchSectionHeader("搜尋結果")
                    LazyColumn {
                        items(uiState.results, key = { it.id }) { city ->
                            CityResultRow(
                                city = city,
                                onClick = { onCitySelected(city) },
                                onToggleFavorite = { viewModel.onIntent(SearchIntent.ToggleFavorite(city)) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun CityResultRow(
    city: City,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f).then(
                Modifier.padding(end = 8.dp)
            )
        ) {
            Text(text = city.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = city.fullDisplayName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (city.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = if (city.isFavorite) "取消收藏" else "加入收藏",
                tint = if (city.isFavorite) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onClick) {
            Icon(Icons.Filled.Search, contentDescription = "查看天氣")
        }
    }
}
