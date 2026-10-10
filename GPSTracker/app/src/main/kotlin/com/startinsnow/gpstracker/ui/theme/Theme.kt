package com.startinsnow.gpstracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.startinsnow.gpstracker.core.model.LocationQuality

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBE7FF),
    onPrimaryContainer = Color(0xFF0A2559),
    inversePrimary = BrandBlueDark,

    secondary = BrandEmerald,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC8F5E0),
    onSecondaryContainer = Color(0xFF03382A),

    tertiary = BrandOrange,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE3D1),
    onTertiaryContainer = Color(0xFF4A1B04),

    background = Color(0xFFF5F7FC),
    onBackground = Color(0xFF101828),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101828),
    surfaceVariant = Color(0xFFE3E9F2),
    onSurfaceVariant = Color(0xFF44474F),
    surfaceTint = BrandBlue,

    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE3E8F1),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F5FB),
    surfaceContainer = Color(0xFFEDF1F8),
    surfaceContainerHigh = Color(0xFFE6ECF5),
    surfaceContainerHighest = Color(0xFFDFE6F0),

    outline = Color(0xFF8C94A3),
    outlineVariant = Color(0xFFD2D9E5),
    scrim = Color(0xFF000000),

    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B)
)

private val DarkColors = darkColorScheme(
    primary = BrandBlueDark,
    onPrimary = Color(0xFF0A1B3D),
    primaryContainer = Color(0xFF1D3B7A),
    onPrimaryContainer = Color(0xFFD6E2FF),
    inversePrimary = BrandBlue,

    secondary = BrandEmeraldDark,
    onSecondary = Color(0xFF00311F),
    secondaryContainer = Color(0xFF0C5136),
    onSecondaryContainer = Color(0xFFB6F2D2),

    tertiary = BrandOrangeDark,
    onTertiary = Color(0xFF4A1B04),
    tertiaryContainer = Color(0xFF6E3410),
    onTertiaryContainer = Color(0xFFFFDCC2),

    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE6EBF5),
    surface = Color(0xFF131C2E),
    onSurface = Color(0xFFE6EBF5),
    surfaceVariant = Color(0xFF2A3448),
    onSurfaceVariant = Color(0xFFC2CAD9),
    surfaceTint = BrandBlueDark,

    surfaceBright = Color(0xFF222D44),
    surfaceDim = Color(0xFF0B1220),
    surfaceContainerLowest = Color(0xFF080D17),
    surfaceContainerLow = Color(0xFF101827),
    surfaceContainer = Color(0xFF141E30),
    surfaceContainerHigh = Color(0xFF1A2438),
    surfaceContainerHighest = Color(0xFF212C42),

    outline = Color(0xFF6B7686),
    outlineVariant = Color(0xFF2C3648),
    scrim = Color(0xFF000000),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

/** 依 GPS 品質回傳語意色，於各畫面一致使用。 */
fun gpsQualityColor(quality: LocationQuality): Color = when (quality) {
    LocationQuality.GOOD -> QualityGood
    LocationQuality.NORMAL -> QualityNormal
    LocationQuality.WEAK -> QualityWeak
    LocationQuality.LOST -> QualityLost
}

@Composable
fun GpsTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
