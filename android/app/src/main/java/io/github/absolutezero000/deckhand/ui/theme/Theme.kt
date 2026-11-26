package io.github.absolutezero000.deckhand.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.absolutezero000.deckhand.data.ThemeMode

val Knew = Color(0xFF2E9E6B)
val Missed = Color(0xFFE0533D)
val Flame = Color(0xFFF59E0B)

/** One accent per deck, picked by the deck's position, so decks are easy to tell apart. */
private val deckAccents = listOf(
    Color(0xFF2A9D8F), // teal
    Color(0xFF5B6CD9), // indigo
    Color(0xFFE76F51), // coral
    Color(0xFFD99A1E), // amber
    Color(0xFF9B5DE5), // violet
    Color(0xFF3FA34D), // green
    Color(0xFFD6457A), // rose
)

fun deckAccent(index: Int): Color = deckAccents[Math.floorMod(index, deckAccents.size)]

/** True when the app is drawing in dark colors (system setting or the in-app choice). */
val LocalDarkTheme = staticCompositionLocalOf { false }

private val Light = lightColorScheme(
    primary = Color(0xFF1D6B7F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCBEAF1),
    onPrimaryContainer = Color(0xFF06303A),
    secondary = Color(0xFF4D6A73),
    secondaryContainer = Color(0xFFDCEAEE),
    onSecondaryContainer = Color(0xFF0F2A31),
    tertiary = Color(0xFFB7791F),
    tertiaryContainer = Color(0xFFFCE8C3),
    background = Color(0xFFF3F6F7),
    onBackground = Color(0xFF151C1F),
    surface = Color(0xFFF3F6F7),
    onSurface = Color(0xFF151C1F),
    surfaceVariant = Color(0xFFE1E8EA),
    onSurfaceVariant = Color(0xFF55646A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFCFC),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE9EFF1),
    surfaceContainerHighest = Color(0xFFDDE5E8),
    outline = Color(0xFFB4C1C6),
    outlineVariant = Color(0xFFD5DEE1),
    error = Color(0xFFC63A2B),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF86D3E3),
    onPrimary = Color(0xFF00363F),
    primaryContainer = Color(0xFF14505F),
    onPrimaryContainer = Color(0xFFCBEAF1),
    secondary = Color(0xFFB3CAD1),
    secondaryContainer = Color(0xFF2A3E44),
    onSecondaryContainer = Color(0xFFD5E7EC),
    tertiary = Color(0xFFF2C14E),
    tertiaryContainer = Color(0xFF4A3A10),
    background = Color(0xFF0D1214),
    onBackground = Color(0xFFE2E8EA),
    surface = Color(0xFF0D1214),
    onSurface = Color(0xFFE2E8EA),
    surfaceVariant = Color(0xFF263136),
    onSurfaceVariant = Color(0xFFA4B3B8),
    surfaceContainerLowest = Color(0xFF080C0D),
    surfaceContainerLow = Color(0xFF131A1C),
    surfaceContainer = Color(0xFF172024),
    surfaceContainerHigh = Color(0xFF1F2A2E),
    surfaceContainerHighest = Color(0xFF2A363B),
    outline = Color(0xFF52636A),
    outlineVariant = Color(0xFF2E3B40),
    error = Color(0xFFFF8A78),
)

private val base = Typography()
private val DeckhandType = Typography(
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, lineHeight = 34.sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(lineHeight = 26.sp),
)

val Overline = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)

private val DeckhandShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun DeckhandTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(
            colorScheme = if (dark) Dark else Light,
            typography = DeckhandType,
            shapes = DeckhandShapes,
            content = content,
        )
    }
}
