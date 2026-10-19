package com.startinsnow.healthtracker.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.startinsnow.healthtracker.data.db.RecordType

/** 品牌色：深藍（圖示磚面）、青綠（圖示漸層）、血滴紅。 */
val BrandBlue = Color(0xFF212C9E)
val BrandTeal = Color(0xFF0E7C8F)
val BrandRed = Color(0xFFD6303E)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2B3AA0), onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E3FF), onPrimaryContainer = Color(0xFF0A1260),
    // 次要色用藍灰：選取狀態（篩選晶片、色調按鈕）不能是紅色，紅色會讓人聯想到警告；血滴紅只用在血糖的圖示與重點
    secondary = Color(0xFF565B8C), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE0FF), onSecondaryContainer = Color(0xFF121749),
    tertiary = Color(0xFF0A7C8C), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFBFEFF7), onTertiaryContainer = Color(0xFF001F25),
    background = Color(0xFFF5F6FD), onBackground = Color(0xFF1A1B24),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1A1B24),
    surfaceVariant = Color(0xFFE3E4F2), onSurfaceVariant = Color(0xFF464757),
    surfaceContainerHighest = Color(0xFFFFFFFF),
    outline = Color(0xFF767889), outlineVariant = Color(0xFFD3D5E6),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB6BEFF), onPrimary = Color(0xFF0F1D7A),
    primaryContainer = Color(0xFF2B3AA0), onPrimaryContainer = Color(0xFFE0E3FF),
    secondary = Color(0xFFBFC3F5), onSecondary = Color(0xFF282E5C),
    secondaryContainer = Color(0xFF3F4575), onSecondaryContainer = Color(0xFFDDE0FF),
    tertiary = Color(0xFF6FD8E8), onTertiary = Color(0xFF00363D),
    tertiaryContainer = Color(0xFF004F58), onTertiaryContainer = Color(0xFFBFEFF7),
    background = Color(0xFF12131C), onBackground = Color(0xFFE4E1EC),
    surface = Color(0xFF1C1D29), onSurface = Color(0xFFE4E1EC),
    surfaceVariant = Color(0xFF2A2B38), onSurfaceVariant = Color(0xFFC6C7DA),
    surfaceContainerHighest = Color(0xFF1C1D29),
    outline = Color(0xFF909199), outlineVariant = Color(0xFF3A3B4A),
    error = Color(0xFFFFB4AB),
)

private val AppTypography = Typography().let {
    it.copy(
        displayMedium = it.displayMedium.copy(fontWeight = FontWeight.Bold),
        titleLarge = it.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = it.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = it.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun HealthTrackerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

/** 統一的卡片：白底、細邊框、無陰影（背景略帶藍灰，卡片自然浮出來）。有 onClick 就可點。 */
@Composable
fun AppCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    val elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    if (onClick == null) {
        Card(modifier, colors = colors, border = border, elevation = elevation, content = content)
    } else {
        Card(onClick = onClick, modifier = modifier, colors = colors, border = border, elevation = elevation, content = content)
    }
}

/** 各類型的代表色與圖示（只用來幫助掃讀，不代表數值好壞）。 */
@Composable
fun recordAccent(type: RecordType): Color = when (type) {
    RecordType.WEIGHT -> MaterialTheme.colorScheme.primary
    RecordType.BODY_FAT -> MaterialTheme.colorScheme.tertiary
    RecordType.BLOOD_GLUCOSE -> if (isSystemInDarkTheme()) Color(0xFFFF8A8A) else BrandRed
    RecordType.BLOOD_PRESSURE -> if (isSystemInDarkTheme()) Color(0xFFD0A8FF) else Color(0xFF8E4EC6)
}

fun recordIcon(type: RecordType): ImageVector = when (type) {
    RecordType.WEIGHT -> Icons.Filled.MonitorWeight
    RecordType.BODY_FAT -> Icons.Filled.Percent
    RecordType.BLOOD_GLUCOSE -> Icons.Filled.WaterDrop
    RecordType.BLOOD_PRESSURE -> Icons.Filled.Favorite
}
