package com.wingman.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Wingman Material 3 design system. Feature screens use MaterialTheme.colorScheme / typography /
 * shapes, WingmanTheme.status for semantic status colors and [Spacing] for padding; they never
 * hard-code colors. Dynamic color is intentionally off so status colors (unverified, unsupported,
 * error) stay recognizable across devices.
 *
 * Palette: a warm coral primary (the brand), plum secondary and teal tertiary, with the full set
 * of M3 surface-container roles so cards, sheets and bars layer consistently in light and dark.
 * On-colors are chosen for at least 4.5:1 contrast against their container.
 */

private val Coral = Color(0xFFB4413A)
private val CoralLight = Color(0xFFFFB4AA)
private val Plum = Color(0xFF6E4F82)
private val PlumLight = Color(0xFFDABDEE)
private val Teal = Color(0xFF00696E)
private val TealLight = Color(0xFF6FD7DD)

val LightColors: ColorScheme = lightColorScheme(
    primary = Coral,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD5),
    onPrimaryContainer = Color(0xFF410001),
    inversePrimary = CoralLight,
    secondary = Plum,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3DAFF),
    onSecondaryContainer = Color(0xFF28093B),
    tertiary = Teal,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFB9F1F4),
    onTertiaryContainer = Color(0xFF002022),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF8F7),
    onBackground = Color(0xFF231918),
    surface = Color(0xFFFFF8F7),
    onSurface = Color(0xFF231918),
    surfaceVariant = Color(0xFFF5DDDA),
    onSurfaceVariant = Color(0xFF534341),
    surfaceTint = Coral,
    inverseSurface = Color(0xFF392E2D),
    inverseOnSurface = Color(0xFFFFEDEA),
    outline = Color(0xFF857370),
    outlineVariant = Color(0xFFD8C2BE),
    scrim = Color.Black,
    surfaceBright = Color(0xFFFFF8F7),
    surfaceDim = Color(0xFFE8D6D3),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFF0EE),
    surfaceContainer = Color(0xFFFCEAE7),
    surfaceContainerHigh = Color(0xFFF7E4E1),
    surfaceContainerHighest = Color(0xFFF1DEDB),
)

val DarkColors: ColorScheme = darkColorScheme(
    primary = CoralLight,
    onPrimary = Color(0xFF690004),
    primaryContainer = Color(0xFF912A24),
    onPrimaryContainer = Color(0xFFFFDAD5),
    inversePrimary = Coral,
    secondary = PlumLight,
    onSecondary = Color(0xFF3E2051),
    secondaryContainer = Color(0xFF553769),
    onSecondaryContainer = Color(0xFFF3DAFF),
    tertiary = TealLight,
    onTertiary = Color(0xFF00373A),
    tertiaryContainer = Color(0xFF004F53),
    onTertiaryContainer = Color(0xFFB9F1F4),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1A1110),
    onBackground = Color(0xFFF1DEDB),
    surface = Color(0xFF1A1110),
    onSurface = Color(0xFFF1DEDB),
    surfaceVariant = Color(0xFF534341),
    onSurfaceVariant = Color(0xFFD8C2BE),
    surfaceTint = CoralLight,
    inverseSurface = Color(0xFFF1DEDB),
    inverseOnSurface = Color(0xFF392E2D),
    outline = Color(0xFFA08C89),
    outlineVariant = Color(0xFF534341),
    scrim = Color.Black,
    surfaceBright = Color(0xFF423735),
    surfaceDim = Color(0xFF1A1110),
    surfaceContainerLowest = Color(0xFF140C0B),
    surfaceContainerLow = Color(0xFF231918),
    surfaceContainer = Color(0xFF271D1C),
    surfaceContainerHigh = Color(0xFF322826),
    surfaceContainerHighest = Color(0xFF3D3231),
)

/**
 * Semantic status colors Material 3 has no role for. [success] marks confirmed states (delivered,
 * connected, VERIFIED); [warning] marks states that need attention but are not failures
 * (UNVERIFIED, paused, canceled). Errors keep using `colorScheme.error`.
 */
@Immutable
data class StatusColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

val LightStatusColors = StatusColors(
    success = Color(0xFF1E6B3A),
    onSuccess = Color.White,
    successContainer = Color(0xFFB7F1C4),
    onSuccessContainer = Color(0xFF00210C),
    warning = Color(0xFF8A5100),
    onWarning = Color.White,
    warningContainer = Color(0xFFFFDDB8),
    onWarningContainer = Color(0xFF2C1600),
)

val DarkStatusColors = StatusColors(
    success = Color(0xFF9CD5A9),
    onSuccess = Color(0xFF003919),
    successContainer = Color(0xFF005227),
    onSuccessContainer = Color(0xFFB7F1C4),
    warning = Color(0xFFFFB960),
    onWarning = Color(0xFF4A2800),
    warningContainer = Color(0xFF693C00),
    onWarningContainer = Color(0xFFFFDDB8),
)

private val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

/** Type scale: M3 defaults, with bolder headlines and titles for clearer hierarchy. */
val WingmanTypography: Typography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
    )
}

/** Monospace style for commands, tokens and IDs (developer-facing text only). */
val MonospaceStyle: TextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp)

/** Corner radii: chips and small badges, cards, sheets and large surfaces. */
val WingmanShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun WingmanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalStatusColors provides if (darkTheme) DarkStatusColors else LightStatusColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = WingmanTypography,
            shapes = WingmanShapes,
            content = content,
        )
    }
}

/** Wingman-specific theme values, read like `MaterialTheme.colorScheme`. */
object WingmanTheme {
    val status: StatusColors
        @Composable
        @ReadOnlyComposable
        get() = LocalStatusColors.current
}
