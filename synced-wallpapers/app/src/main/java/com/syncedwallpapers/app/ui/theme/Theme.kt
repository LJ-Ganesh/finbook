package com.syncedwallpapers.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// One accent (calm sage green) on neutral surfaces. No dynamic color: the app should
// look the same whatever wallpaper is live.
private val Accent = Color(0xFF3F6B5C)
private val AccentDark = Color(0xFF9CCFBA)

private val Light = lightColorScheme(
    primary = Accent, onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E8DF), onPrimaryContainer = Color(0xFF0F2A21),
    secondary = Color(0xFF5B615E), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E9E7), onSecondaryContainer = Color(0xFF1B1D1C),
    background = Color(0xFFFAFAF8), onBackground = Color(0xFF1B1C1B),
    surface = Color(0xFFFAFAF8), onSurface = Color(0xFF1B1C1B),
    surfaceVariant = Color(0xFFE5E7E5), onSurfaceVariant = Color(0xFF5A5E5C),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF3F4F2),
    surfaceContainer = Color(0xFFEEEFED), surfaceContainerHigh = Color(0xFFE8E9E7),
    outline = Color(0xFF8A8F8C), outlineVariant = Color(0xFFD3D6D4),
    error = Color(0xFFB3261E), errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
)

private val Dark = darkColorScheme(
    primary = AccentDark, onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF26503F), onPrimaryContainer = Color(0xFFD5E8DF),
    secondary = Color(0xFFC2C7C4), onSecondary = Color(0xFF2C312F),
    secondaryContainer = Color(0xFF3A3F3D), onSecondaryContainer = Color(0xFFE0E3E1),
    background = Color(0xFF141615), onBackground = Color(0xFFE3E3E1),
    surface = Color(0xFF141615), onSurface = Color(0xFFE3E3E1),
    surfaceVariant = Color(0xFF404543), onSurfaceVariant = Color(0xFFC0C4C2),
    surfaceContainerLowest = Color(0xFF0F1110), surfaceContainerLow = Color(0xFF1C1E1D),
    surfaceContainer = Color(0xFF202221), surfaceContainerHigh = Color(0xFF2A2C2B),
    outline = Color(0xFF8A8F8C), outlineVariant = Color(0xFF404543),
    error = Color(0xFFF2B8B5), errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
)

val Amber = Color(0xFFC98A1B)
val Green = Color(0xFF3F8F5F)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp),
)

private val base = Typography()
private val AppType = base.copy(
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
)

@Composable
fun SyncedTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = AppType, shapes = AppShapes, content = content)
}
