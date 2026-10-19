package com.startinsnow.healthtracker.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.startinsnow.healthtracker.cloud.CloudSync
import com.startinsnow.healthtracker.data.prefs.UserSettingsRepository
import com.startinsnow.healthtracker.data.repository.HealthRecordRepository

private const val ROUTE_OVERVIEW = "overview"
private const val ROUTE_RECORDS = "records"
private const val ROUTE_SETTINGS = "settings"

@Composable
fun HealthTrackerRoot(records: HealthRecordRepository, settings: UserSettingsRepository, cloud: CloudSync) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf(
                    Triple(ROUTE_OVERVIEW, "總覽", Icons.Filled.Home),
                    Triple(ROUTE_RECORDS, "紀錄", Icons.AutoMirrored.Filled.List),
                    Triple(ROUTE_SETTINGS, "設定", Icons.Filled.Settings),
                ).forEach { (route, label, icon) ->
                    NavigationBarItem(
                        selected = current?.hierarchy?.any { it.route == route } == true,
                        onClick = {
                            nav.navigate(route) {
                                launchSingleTop = true
                                restoreState = true
                                popUpTo(ROUTE_OVERVIEW) { saveState = true }
                            }
                        },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = ROUTE_OVERVIEW, modifier = Modifier.padding(padding)) {
            composable(ROUTE_OVERVIEW) {
                OverviewScreen(records, cloud, settings, onSeeAll = {
                    nav.navigate(ROUTE_RECORDS) {
                        launchSingleTop = true
                        restoreState = true
                        popUpTo(ROUTE_OVERVIEW) { saveState = true }
                    }
                })
            }
            composable(ROUTE_RECORDS) { RecordsScreen(records, settings) }
            composable(ROUTE_SETTINGS) { SettingsScreen(settings, records, cloud) }
        }
    }
}
