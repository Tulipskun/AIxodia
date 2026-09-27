package com.tulipskun.aixodia.ui.theme

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

private val Ink900 = Color(0xFF0E1413)
private val Ink800 = Color(0xFF161D1C)
private val Ink700 = Color(0xFF1A211F)
private val Ink600 = Color(0xFF242B2A)
private val Ink500 = Color(0xFF2F3634)
private val Mist100 = Color(0xFFDEE5E3)
private val Mist300 = Color(0xFFBEC9C6)
private val Mist500 = Color(0xFF89938F)
private val Mint300 = Color(0xFF7EE0C4)
private val Mint900 = Color(0xFF00382E)
private val Mint800 = Color(0xFF005143)
private val Mint200 = Color(0xFF9FFFE0)
private val Teal300 = Color(0xFFB6CCCB)
private val Teal900 = Color(0xFF1A3533)
private val Teal800 = Color(0xFF334B4A)
private val Teal200 = Color(0xFFD2E8E7)
private val Amber300 = Color(0xFFF5C36B)
private val Amber900 = Color(0xFF432C00)
private val Amber800 = Color(0xFF5F4100)
private val Amber200 = Color(0xFFFFE1A3)
private val Paper = Color(0xFFF5FBF8)
private val PaperContainer = Color(0xFFE9EFEC)

private val DarkColors = darkColorScheme(
    primary = Mint300,
    onPrimary = Mint900,
    primaryContainer = Mint800,
    onPrimaryContainer = Mint200,
    secondary = Teal300,
    onSecondary = Teal900,
    secondaryContainer = Teal800,
    onSecondaryContainer = Teal200,
    tertiary = Amber300,
    onTertiary = Amber900,
    tertiaryContainer = Amber800,
    onTertiaryContainer = Amber200,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Ink900,
    onBackground = Mist100,
    surface = Ink900,
    onSurface = Mist100,
    surfaceVariant = Color(0xFF3F4947),
    onSurfaceVariant = Mist300,
    surfaceTint = Mint300,
    inverseSurface = Mist100,
    inverseOnSurface = Ink900,
    outline = Mist500,
    outlineVariant = Color(0xFF3F4947),
    surfaceBright = Ink600,
    surfaceDim = Ink900,
    surfaceContainerLowest = Color(0xFF090F0E),
    surfaceContainerLow = Ink800,
    surfaceContainer = Ink700,
    surfaceContainerHigh = Ink600,
    surfaceContainerHighest = Ink500,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B58),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Mint200,
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF4A6360),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E4),
    onSecondaryContainer = Color(0xFF06201E),
    tertiary = Color(0xFF7B5800),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDEA6),
    onTertiaryContainer = Color(0xFF261A00),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Paper,
    onBackground = Color(0xFF171D1C),
    surface = Paper,
    onSurface = Color(0xFF171D1C),
    surfaceVariant = Color(0xFFDAE5E1),
    onSurfaceVariant = Color(0xFF3F4947),
    surfaceTint = Color(0xFF006B58),
    inverseSurface = Color(0xFF2B3231),
    inverseOnSurface = Color(0xFFECF2EF),
    outline = Color(0xFF6F7976),
    outlineVariant = Color(0xFFBEC9C6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFF5F2),
    surfaceContainer = PaperContainer,
    surfaceContainerHigh = Color(0xFFE3E9E7),
    surfaceContainerHighest = Color(0xFFDDE4E1),
)

private val AppTypography = Typography(
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.15.sp),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AIxodiaTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
