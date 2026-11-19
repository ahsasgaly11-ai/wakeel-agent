package com.wakeel.agent.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Brand {
    val Indigo = Color(0xFF6366F1)
    val IndigoDeep = Color(0xFF4338CA)
    val Teal = Color(0xFF14B8A6)
    val Amber = Color(0xFFF59E0B)
    val Green = Color(0xFF10B981)
    val Red = Color(0xFFEF4444)
}

private val Dark = darkColorScheme(
    primary = Color(0xFF8B93FF),
    onPrimary = Color(0xFF0B0F19),
    primaryContainer = Color(0xFF2A2875),
    onPrimaryContainer = Color(0xFFE0E0FF),
    secondary = Color(0xFF2DD4BF),
    onSecondary = Color(0xFF042F2E),
    secondaryContainer = Color(0xFF0F3D3A),
    onSecondaryContainer = Color(0xFFCCFBF1),
    tertiary = Color(0xFFFBBF24),
    background = Color(0xFF0B0F19),
    onBackground = Color(0xFFE5E7EB),
    surface = Color(0xFF0B0F19),
    onSurface = Color(0xFFE5E7EB),
    surfaceVariant = Color(0xFF1F2937),
    onSurfaceVariant = Color(0xFF9CA3AF),
    surfaceContainerLowest = Color(0xFF080B13),
    surfaceContainerLow = Color(0xFF0F1420),
    surfaceContainer = Color(0xFF131A28),
    surfaceContainerHigh = Color(0xFF1A2232),
    surfaceContainerHighest = Color(0xFF222B3D),
    outline = Color(0xFF374151),
    outlineVariant = Color(0xFF243044),
    error = Color(0xFFF87171),
)

private val Light = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Color(0xFF0D9488),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF042F2E),
    tertiary = Color(0xFFD97706),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFF6F7FB),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFEEF0F6),
    onSurfaceVariant = Color(0xFF6B7280),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFBFE),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF1F3F9),
    surfaceContainerHighest = Color(0xFFE9ECF4),
    outline = Color(0xFFD1D5DB),
    outlineVariant = Color(0xFFE5E7EB),
    error = Color(0xFFDC2626),
)

private val base = Typography()
private val AppTypography = base.copy(
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 28.sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun WakeelTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
