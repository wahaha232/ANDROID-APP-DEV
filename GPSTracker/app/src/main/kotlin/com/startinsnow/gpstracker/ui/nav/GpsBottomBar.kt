package com.startinsnow.gpstracker.ui.nav

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.startinsnow.gpstracker.R

/** 底部導航的三個主要頁面。 */
enum class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector
) {
    HOME(Routes.HOME, R.string.nav_home, Icons.Filled.Home),
    HISTORY(Routes.HISTORY, R.string.nav_history, Icons.Filled.History),
    SETTINGS(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings)
}

@Composable
fun GpsBottomBar(
    currentRoute: String?,
    onSelect: (TopLevelDestination) -> Unit
) {
    NavigationBar {
        TopLevelDestination.entries.forEach { destination ->
            val label = stringResource(destination.labelRes)
            NavigationBarItem(
                selected = currentRoute == destination.route,
                onClick = { onSelect(destination) },
                icon = { Icon(destination.icon, contentDescription = label) },
                label = { Text(label) },
                colors = NavigationBarItemDefaults.colors()
            )
        }
    }
}
