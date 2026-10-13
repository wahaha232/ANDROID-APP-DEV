package com.startinsnow.gpstracker.ui.nav

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.startinsnow.gpstracker.ui.detail.TrackDetailScreen
import com.startinsnow.gpstracker.ui.flythrough.FlythroughScreen
import com.startinsnow.gpstracker.ui.history.HistoryScreen
import com.startinsnow.gpstracker.ui.home.HomeScreen
import com.startinsnow.gpstracker.ui.recording.RecordingScreen
import com.startinsnow.gpstracker.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val RECORDING = "recording?resumeTrackId={resumeTrackId}"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val RECAP = "recap"
    const val DETAIL = "detail/{trackId}"
    const val FLYTHROUGH = "flythrough/{trackId}"
    fun detail(trackId: String) = "detail/$trackId"
    fun flythrough(trackId: String) = "flythrough/$trackId"
    fun recording(resumeTrackId: String? = null) =
        if (resumeTrackId == null) "recording" else "recording?resumeTrackId=$resumeTrackId"
}

@Composable
fun GpsTrackerNavHost(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = TopLevelDestination.entries.any { it.route == currentRoute }

    // 根 Scaffold 只負責底部導航；各畫面自行處理狀態列（透過 TopAppBar），
    // 因此 contentWindowInsets 設為 0，避免與畫面內的 Scaffold 重複 padding。
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                GpsBottomBar(currentRoute = currentRoute) { destination ->
                    navController.navigate(destination.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onStartRecording = { navController.navigate(Routes.recording()) { launchSingleTop = true } },
                    onResumeRecording = { trackId -> navController.navigate(Routes.recording(trackId)) { launchSingleTop = true } },
                    onOpenHistory = { navController.navigate(Routes.HISTORY) { launchSingleTop = true } },
                    onOpenRecap = { navController.navigate(Routes.RECAP) { launchSingleTop = true } },
                    onOpenTrack = { trackId -> navController.navigate(Routes.detail(trackId)) { launchSingleTop = true } }
                )
            }
            composable(
                Routes.RECORDING,
                arguments = listOf(
                    navArgument("resumeTrackId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { entry ->
                RecordingScreen(
                    resumeTrackId = entry.arguments?.getString("resumeTrackId"),
                    onClose = { navController.popBackStack() },
                    onFinished = { trackId ->
                        navController.popBackStack(Routes.HOME, inclusive = false)
                        if (trackId != null) navController.navigate(Routes.detail(trackId)) { launchSingleTop = true }
                    }
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen(onOpenTrack = { trackId -> navController.navigate(Routes.detail(trackId)) { launchSingleTop = true } })
            }
            composable(Routes.RECAP) {
                com.startinsnow.gpstracker.ui.recap.RecapScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen()
            }
            composable(
                Routes.DETAIL,
                arguments = listOf(navArgument("trackId") { })
            ) { entry ->
                val trackId = entry.arguments?.getString("trackId") ?: return@composable
                TrackDetailScreen(
                    trackId = trackId,
                    onBack = { navController.popBackStack() },
                    onOpenFlythrough = { navController.navigate(Routes.flythrough(it)) { launchSingleTop = true } }
                )
            }
            composable(
                Routes.FLYTHROUGH,
                arguments = listOf(navArgument("trackId") { })
            ) { entry ->
                val trackId = entry.arguments?.getString("trackId") ?: return@composable
                FlythroughScreen(trackId = trackId, onBack = { navController.popBackStack() })
            }
        }
    }
}
