package app.takt.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.takt.R

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun inter(w: Int) = Font(R.font.inter, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700), inter(800))

private val dark = darkColorScheme(
    primary = Color(0xFF3DDC97), onPrimary = Color(0xFF032414),
    primaryContainer = Color(0xFF123A2A), onPrimaryContainer = Color(0xFFB8F5D8),
    secondary = Color(0xFF8AB4FF), onSecondary = Color(0xFF0A1C3A),
    background = Color(0xFF0D0F12), onBackground = Color(0xFFECEFF3),
    surface = Color(0xFF15181C), onSurface = Color(0xFFECEFF3),
    surfaceVariant = Color(0xFF22262C), onSurfaceVariant = Color(0xFF9AA3AF),
    surfaceContainerLowest = Color(0xFF0D0F12), surfaceContainerLow = Color(0xFF15181C),
    surfaceContainer = Color(0xFF1A1E23), surfaceContainerHigh = Color(0xFF22262C), surfaceContainerHighest = Color(0xFF2A2F36),
    outline = Color(0xFF8A93A0), outlineVariant = Color(0xFF2C3138),
    error = Color(0xFFFF6B6B),
)

private val light = lightColorScheme(
    primary = Color(0xFF0A8F5E), onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F5E5), onPrimaryContainer = Color(0xFF00391F),
    secondary = Color(0xFF2F6FDB), onSecondary = Color.White,
    background = Color(0xFFF3F5F7), onBackground = Color(0xFF101317),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF101317),
    surfaceVariant = Color(0xFFEDF0F3), onSurfaceVariant = Color(0xFF5E6773),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8F9FA),
    surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFEDF0F3), surfaceContainerHighest = Color(0xFFE3E7EB),
    outline = Color(0xFF6B7480), outlineVariant = Color(0xFFE1E5EA),
    error = Color(0xFFD93A3A),
)

private fun t(size: Int, weight: Int, line: Int = kotlin.math.ceil(size * 1.38).toInt(), spacing: Float = 0f) =
    TextStyle(fontFamily = Inter, fontWeight = FontWeight(weight), fontSize = size.sp, lineHeight = line.sp, letterSpacing = spacing.sp)

private val typography = Typography(
    displaySmall = t(34, 800, spacing = -0.8f),
    headlineLarge = t(30, 800, spacing = -0.6f),
    headlineMedium = t(26, 750, spacing = -0.4f),
    headlineSmall = t(22, 700, spacing = -0.3f),
    titleLarge = t(20, 700, spacing = -0.2f),
    titleMedium = t(16, 600),
    titleSmall = t(14, 600),
    bodyLarge = t(16, 400),
    bodyMedium = t(14, 400),
    bodySmall = t(12, 400),
    labelLarge = t(14, 600),
    labelMedium = t(12, 600),
    labelSmall = t(11, 600, spacing = 0.2f),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp),
)

val LocalDark = compositionLocalOf { false }

@Composable
fun TaktTheme(content: @Composable () -> Unit) {
    val d = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (d) dark else light, typography = typography, shapes = shapes) {
        androidx.compose.runtime.CompositionLocalProvider(LocalDark provides d, content = content)
    }
}
