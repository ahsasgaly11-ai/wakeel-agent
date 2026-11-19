package com.wakeel.agent.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wakeel.agent.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Jewel-tone identity: emerald and gold on deep green-black, with sapphire and ruby accents. */
object Brand {
    val Emerald = Color(0xFF2FCF9A)
    val EmeraldDeep = Color(0xFF0B6B55)
    val Gold = Color(0xFFE3B95A)
    val Sapphire = Color(0xFF6FA8FF)
    val Ruby = Color(0xFFE5484D)

    // Semantic aliases
    val Green = Color(0xFF2BC48A)
    val Amber = Color(0xFFE0A93B)
    val Red = Ruby
    val Teal = Emerald
}

val Tajawal = FontFamily(
    Font(R.font.tajawal_regular, FontWeight.Normal),
    Font(R.font.tajawal_medium, FontWeight.Medium),
    Font(R.font.tajawal_bold, FontWeight.SemiBold),
    Font(R.font.tajawal_bold, FontWeight.Bold),
    Font(R.font.tajawal_extrabold, FontWeight.ExtraBold),
)

/** Calligraphic face for the wordmark only. */
val ArefRuqaa = FontFamily(Font(R.font.aref_ruqaa_bold, FontWeight.Bold))

private val Dark = darkColorScheme(
    primary = Color(0xFF3DD9A4),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF0E4A3A),
    onPrimaryContainer = Color(0xFFB6F5DD),
    secondary = Color(0xFFE8C36A),
    onSecondary = Color(0xFF3A2B00),
    secondaryContainer = Color(0xFF453610),
    onSecondaryContainer = Color(0xFFFFE8A8),
    tertiary = Color(0xFF7FB2FF),
    onTertiary = Color(0xFF002E66),
    background = Color(0xFF08110F),
    onBackground = Color(0xFFE6F0EC),
    surface = Color(0xFF08110F),
    onSurface = Color(0xFFE6F0EC),
    surfaceVariant = Color(0xFF1A2B26),
    onSurfaceVariant = Color(0xFF9DB3AB),
    surfaceContainerLowest = Color(0xFF060D0B),
    surfaceContainerLow = Color(0xFF0C1714),
    surfaceContainer = Color(0xFF101E1A),
    surfaceContainerHigh = Color(0xFF152621),
    surfaceContainerHighest = Color(0xFF1B302A),
    outline = Color(0xFF2F5046),
    outlineVariant = Color(0xFF1D332C),
    error = Color(0xFFFF6B7A),
    onError = Color(0xFF4A0010),
)

private val Light = lightColorScheme(
    primary = Color(0xFF0B6B55),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEFE2),
    onPrimaryContainer = Color(0xFF00382A),
    secondary = Color(0xFF8A6A12),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF6E3B0),
    onSecondaryContainer = Color(0xFF2B2000),
    tertiary = Color(0xFF2F5FB3),
    onTertiary = Color.White,
    background = Color(0xFFF7F4EC),
    onBackground = Color(0xFF18201D),
    surface = Color(0xFFF7F4EC),
    onSurface = Color(0xFF18201D),
    surfaceVariant = Color(0xFFEDE8DB),
    onSurfaceVariant = Color(0xFF5E6B66),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBF9F3),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF1EDE2),
    surfaceContainerHighest = Color(0xFFE8E3D5),
    outline = Color(0xFFC9C3B3),
    outlineVariant = Color(0xFFE2DDCF),
    error = Color(0xFFC2273A),
    onError = Color.White,
)

private fun TextStyle.tj(weight: FontWeight? = null) =
    copy(fontFamily = Tajawal, fontWeight = weight ?: fontWeight)

private val base = Typography()
private val AppTypography = Typography(
    displayLarge = base.displayLarge.tj(FontWeight.ExtraBold),
    displayMedium = base.displayMedium.tj(FontWeight.ExtraBold),
    displaySmall = base.displaySmall.tj(FontWeight.ExtraBold),
    headlineLarge = base.headlineLarge.tj(FontWeight.ExtraBold),
    headlineMedium = base.headlineMedium.tj(FontWeight.ExtraBold).copy(fontSize = 26.sp),
    headlineSmall = base.headlineSmall.tj(FontWeight.Bold),
    titleLarge = base.titleLarge.tj(FontWeight.Bold).copy(fontSize = 21.sp),
    titleMedium = base.titleMedium.tj(FontWeight.Bold).copy(fontSize = 17.sp),
    titleSmall = base.titleSmall.tj(FontWeight.Bold).copy(fontSize = 15.sp),
    bodyLarge = base.bodyLarge.tj().copy(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = base.bodyMedium.tj().copy(fontSize = 14.5.sp, lineHeight = 23.sp),
    bodySmall = base.bodySmall.tj().copy(fontSize = 12.5.sp, lineHeight = 19.sp),
    labelLarge = base.labelLarge.tj(FontWeight.Bold).copy(fontSize = 14.5.sp),
    labelMedium = base.labelMedium.tj(FontWeight.Medium).copy(fontSize = 12.5.sp),
    labelSmall = base.labelSmall.tj(FontWeight.Medium).copy(fontSize = 11.5.sp),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp),
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

/** A faint lattice of eight-pointed stars (khatam), the signature of Islamic geometric ornament. */
fun Modifier.khatam(color: Color, cell: Float = 92f): Modifier = drawBehind {
    val r = cell * 0.30f
    val stroke = Stroke(width = 1.4f)
    var row = 0
    var y = cell / 2f
    while (y < size.height + cell) {
        var x = if (row % 2 == 0) cell / 2f else cell
        while (x < size.width + cell) {
            for (k in 0..1) {
                val path = Path()
                for (i in 0 until 4) {
                    val a = (PI / 2 * i + PI / 4 * k).toFloat()
                    val p = Offset(x + r * cos(a), y + r * sin(a))
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                path.close()
                drawPath(path, color, style = stroke)
            }
            x += cell
        }
        y += cell * 0.86f
        row++
    }
}
