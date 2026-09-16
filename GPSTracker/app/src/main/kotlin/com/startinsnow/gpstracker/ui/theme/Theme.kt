package com.startinsnow.gpstracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GpsPrimary = Color(0xFF1565C0)
private val GpsAccent = Color(0xFF00C853)
private val GpsBackground = Color(0xFF0B1220)
private val GpsSurface = Color(0xFF131C2E)

private val DarkColors = darkColorScheme(
    primary = GpsPrimary,
    secondary = GpsAccent,
    background = GpsBackground,
    surface = GpsSurface
)

private val LightColors = lightColorScheme(
    primary = GpsPrimary,
    secondary = GpsAccent
)

@Composable
fun GpsTrackerTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
