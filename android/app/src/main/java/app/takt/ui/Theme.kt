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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.lerp
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

/** Accent colours to pick in the settings: [dark] on the dark theme, [light] (darker, white text on it) on the light one. */
class Accent(val key: String, val label: String, val dark: Color, val light: Color)
val ACCENTS = listOf(
    Accent("green", "Grün", Color(0xFF3DDC97), Color(0xFF0A8F5E)),
    Accent("teal", "Türkis", Color(0xFF34D3D9), Color(0xFF00808A)),
    Accent("blue", "Blau", Color(0xFF6AA8FF), Color(0xFF1F63D6)),
    Accent("violet", "Violett", Color(0xFFB48CFF), Color(0xFF7344D6)),
    Accent("pink", "Pink", Color(0xFFFF7AB8), Color(0xFFC4246F)),
    Accent("orange", "Orange", Color(0xFFFFA552), Color(0xFFC2570A)),
    Accent("yellow", "Gelb", Color(0xFFF5D04A), Color(0xFF8A6D00)),
)
/** "system": the wallpaper colours of Android 12 and later */
val dynamicAccent = android.os.Build.VERSION.SDK_INT >= 31

private fun accented(base: androidx.compose.material3.ColorScheme, key: String, d: Boolean, ctx: android.content.Context): androidx.compose.material3.ColorScheme {
    if (key == "green") return base
    if (key == "system" && dynamicAccent) {
        val s = if (d) androidx.compose.material3.dynamicDarkColorScheme(ctx) else androidx.compose.material3.dynamicLightColorScheme(ctx)
        return base.copy(primary = s.primary, onPrimary = s.onPrimary, primaryContainer = s.primaryContainer, onPrimaryContainer = s.onPrimaryContainer)
    }
    val a = ACCENTS.firstOrNull { it.key == key } ?: return base
    return if (d) base.copy(primary = a.dark, onPrimary = lerp(a.dark, Color.Black, 0.85f),
        primaryContainer = lerp(base.background, a.dark, 0.22f), onPrimaryContainer = lerp(a.dark, Color.White, 0.65f))
    else base.copy(primary = a.light, onPrimary = Color.White,
        primaryContainer = lerp(Color.White, a.light, 0.16f), onPrimaryContainer = lerp(a.light, Color.Black, 0.55f))
}

/** The accent as "#RRGGBB" for map layers (they are drawn on the map's own dark or light style). */
fun accentHex(key: String, dark: Boolean, ctx: android.content.Context): String {
    val c = accented(if (dark) darkScheme else lightScheme, key, dark, ctx).primary
    return String.format("#%06X", 0xFFFFFF and c.toArgb())
}
private val darkScheme get() = dark
private val lightScheme get() = light

@Composable
fun TaktTheme(content: @Composable () -> Unit) {
    val d = isSystemInDarkTheme()
    val accent by app.takt.TaktApp.instance.prefs.accent.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scheme = androidx.compose.runtime.remember(d, accent) { accented(if (d) dark else light, accent, d, ctx) }
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes) {
        androidx.compose.runtime.CompositionLocalProvider(LocalDark provides d, content = content)
    }
}
