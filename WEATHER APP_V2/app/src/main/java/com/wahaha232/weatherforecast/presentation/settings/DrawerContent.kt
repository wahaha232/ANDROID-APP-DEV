// D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/src/main/java/com/wahaha232/weatherforecast/presentation/settings/DrawerContent.kt
package com.wahaha232.weatherforecast.presentation.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.EditLocation
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wahaha232.weatherforecast.domain.model.AppSettings
import com.wahaha232.weatherforecast.domain.model.City
import com.wahaha232.weatherforecast.domain.model.PrecipitationUnit
import com.wahaha232.weatherforecast.domain.model.PressureUnit
import com.wahaha232.weatherforecast.domain.model.TemperatureUnit
import com.wahaha232.weatherforecast.domain.model.VisibilityUnit
import com.wahaha232.weatherforecast.domain.model.WindSpeedUnit

/**
 * 左側抽屜選單內容：城市清單、功能開關、單位設定、App 資訊項目。
 * 對齊使用者提供的截圖版面配置。
 */
@Composable
fun WeatherDrawerContent(
    currentCity: City,
    favoriteCities: List<City>,
    settings: AppSettings,
    onSelectCity: (City) -> Unit,
    onUseCurrentLocation: () -> Unit,
    onEditLocation: () -> Unit,
    onUpdateSettings: (AppSettings) -> Unit,
    onRateApp: () -> Unit,
    onSendFeedback: () -> Unit,
    onShareApp: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    versionName: String,
    modifier: Modifier = Modifier
) {
    ModalDrawerSheet(modifier = modifier) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp)
        ) {
            // ---------- Header ----------
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Cloud, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(text = "天氣", style = MaterialTheme.typography.titleLarge)
            }
            HorizontalDivider()

            // ---------- Location Section ----------
            DrawerActionRow(Icons.Filled.EditLocation, "編輯位置", onClick = onEditLocation)
            DrawerActionRow(Icons.Filled.MyLocation, "當前位置", onClick = onUseCurrentLocation)
            favoriteCities.forEach { city ->
                DrawerActionRow(
                    icon = Icons.Filled.Star,
                    label = city.name,
                    onClick = { onSelectCity(city) },
                    highlighted = city.id == currentCity.id
                )
            }
            HorizontalDivider()

            // ---------- Feature Toggles ----------
            SectionLabel("功能")
            DrawerSwitchRow(
                icon = Icons.Filled.Widgets,
                label = "每日天氣",
                checked = settings.dailyWeatherNotificationEnabled,
                onCheckedChange = { onUpdateSettings(settings.copy(dailyWeatherNotificationEnabled = it)) }
            )
            DrawerSwitchRow(
                icon = Icons.Filled.Cloud,
                label = "天氣背景",
                checked = settings.weatherBackgroundEnabled,
                onCheckedChange = { onUpdateSettings(settings.copy(weatherBackgroundEnabled = it)) }
            )
            DrawerSwitchRow(
                icon = Icons.Filled.Widgets,
                label = "顯示夜間資訊",
                checked = settings.showNightInfo,
                onCheckedChange = { onUpdateSettings(settings.copy(showNightInfo = it)) }
            )

            // ---------- Unit Settings ----------
            UnitSelectorRow(
                label = "溫度單位",
                currentLabel = if (settings.temperatureUnit == TemperatureUnit.CELSIUS) "°C" else "°F",
                options = listOf(TemperatureUnit.CELSIUS to "°C", TemperatureUnit.FAHRENHEIT to "°F"),
                onSelect = { onUpdateSettings(settings.copy(temperatureUnit = it)) }
            )
            UnitSelectorRow(
                label = "風單位",
                currentLabel = settings.windSpeedUnit.labelText(),
                options = listOf(
                    WindSpeedUnit.KMH to "km/h",
                    WindSpeedUnit.MPH to "mph",
                    WindSpeedUnit.MS to "m/s"
                ),
                onSelect = { onUpdateSettings(settings.copy(windSpeedUnit = it)) }
            )
            UnitSelectorRow(
                label = "壓強單位",
                currentLabel = settings.pressureUnit.labelText(),
                options = listOf(
                    PressureUnit.MBAR to "mbar",
                    PressureUnit.MMHG to "mmHg",
                    PressureUnit.INHG to "inHg"
                ),
                onSelect = { onUpdateSettings(settings.copy(pressureUnit = it)) }
            )
            UnitSelectorRow(
                label = "能見度單位",
                currentLabel = settings.visibilityUnit.labelText(),
                options = listOf(VisibilityUnit.KM to "km", VisibilityUnit.MI to "mi"),
                onSelect = { onUpdateSettings(settings.copy(visibilityUnit = it)) }
            )
            UnitSelectorRow(
                label = "降水單位",
                currentLabel = settings.precipitationUnit.labelText(),
                options = listOf(PrecipitationUnit.MM to "mm", PrecipitationUnit.IN to "in"),
                onSelect = { onUpdateSettings(settings.copy(precipitationUnit = it)) }
            )
            HorizontalDivider()

            // ---------- App Info ----------
            DrawerActionRow(Icons.Filled.RateReview, "評價我", onClick = onRateApp)
            DrawerActionRow(Icons.Filled.Info, "反饋意見", onClick = onSendFeedback)
            DrawerActionRow(Icons.Filled.Share, "分享給朋友", onClick = onShareApp)
            DrawerActionRow(Icons.Filled.PrivacyTip, "隱私政策", onClick = onOpenPrivacyPolicy)
            DrawerActionRow(Icons.Filled.Info, "版本", onClick = {}, trailingText = versionName)
        }
    }
}

private fun WindSpeedUnit.labelText() = when (this) {
    WindSpeedUnit.KMH -> "km/h"
    WindSpeedUnit.MPH -> "mph"
    WindSpeedUnit.MS -> "m/s"
}

private fun PressureUnit.labelText() = when (this) {
    PressureUnit.MBAR -> "mbar"
    PressureUnit.MMHG -> "mmHg"
    PressureUnit.INHG -> "inHg"
}

private fun VisibilityUnit.labelText() = when (this) {
    VisibilityUnit.KM -> "km"
    VisibilityUnit.MI -> "mi"
}

private fun PrecipitationUnit.labelText() = when (this) {
    PrecipitationUnit.MM -> "mm"
    PrecipitationUnit.IN -> "in"
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
    )
}

@Composable
private fun DrawerActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
    trailingText: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = label,
                modifier = Modifier.padding(start = 16.dp),
                color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        }
        if (trailingText != null) {
            Text(text = trailingText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DrawerSwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null)
            Text(text = label, modifier = Modifier.padding(start = 16.dp))
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun <T> UnitSelectorRow(
    label: String,
    currentLabel: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label)
        Column {
            Text(text = currentLabel, color = MaterialTheme.colorScheme.primary)
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            onSelect(value)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

