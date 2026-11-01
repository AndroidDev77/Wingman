package com.wingman.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * Wingman Material 3 design system. Feature screens use MaterialTheme.colorScheme / typography
 * only; they never hard-code colors. Dynamic color is intentionally off so status colors
 * (unverified, unsupported, error) stay recognizable across devices.
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
    secondary = Plum,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3DAFF),
    onSecondaryContainer = Color(0xFF28093B),
    tertiary = Teal,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF6FF6FC),
    onTertiaryContainer = Color(0xFF002022),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFFBFF),
    onBackground = Color(0xFF201A19),
    surface = Color(0xFFFFFBFF),
    onSurface = Color(0xFF201A19),
    surfaceVariant = Color(0xFFF5DDDA),
    onSurfaceVariant = Color(0xFF534341),
    outline = Color(0xFF857370),
)

val DarkColors: ColorScheme = darkColorScheme(
    primary = CoralLight,
    onPrimary = Color(0xFF690004),
    primaryContainer = Color(0xFF912A24),
    onPrimaryContainer = Color(0xFFFFDAD5),
    secondary = PlumLight,
    onSecondary = Color(0xFF3E2051),
    secondaryContainer = Color(0xFF553769),
    onSecondaryContainer = Color(0xFFF3DAFF),
    tertiary = TealLight,
    onTertiary = Color(0xFF00373A),
    tertiaryContainer = Color(0xFF004F53),
    onTertiaryContainer = Color(0xFF6FF6FC),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF201A19),
    onBackground = Color(0xFFEDE0DE),
    surface = Color(0xFF201A19),
    onSurface = Color(0xFFEDE0DE),
    surfaceVariant = Color(0xFF534341),
    onSurfaceVariant = Color(0xFFD8C2BE),
    outline = Color(0xFFA08C89),
)

val WingmanTypography: Typography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
    )
}

@Composable
fun WingmanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = WingmanTypography,
        content = content,
    )
}
