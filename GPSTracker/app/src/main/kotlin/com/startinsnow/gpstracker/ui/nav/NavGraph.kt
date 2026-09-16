package com.startinsnow.gpstracker.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.startinsnow.gpstracker.ui.detail.TrackDetailScreen
import com.startinsnow.gpstracker.ui.history.HistoryScreen
import com.startinsnow.gpstracker.ui.home.HomeScreen
import com.startinsnow.gpstracker.ui.recording.RecordingScreen
import com.startinsnow.gpstracker.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val RECORDING = "recording"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val DETAIL = "detail/{trackId}"
    fun detail(trackId: String) = "detail/$trackId"
}

@Composable
fun GpsTrackerNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onStartRecording = { navController.navigate(Routes.RECORDING) },
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }
        composable(Routes.RECORDING) {
            RecordingScreen(
                onFinished = { trackId ->
                    navController.popBackStack(Routes.HOME, inclusive = false)
                    if (trackId != null) navController.navigate(Routes.detail(trackId))
                }
            )
        }
        composable(Routes.HISTORY) {
            HistoryScreen(onOpenTrack = { trackId -> navController.navigate(Routes.detail(trackId)) })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen()
        }
        composable(
            Routes.DETAIL,
            arguments = listOf(navArgument("trackId") { })
        ) { backStackEntry ->
            val trackId = backStackEntry.arguments?.getString("trackId") ?: return@composable
            TrackDetailScreen(trackId = trackId, onBack = { navController.popBackStack() })
        }
    }
}
