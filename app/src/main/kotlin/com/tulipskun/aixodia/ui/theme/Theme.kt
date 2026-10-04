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

private val Navy950 = Color(0xFF0B1220)
private val Navy900 = Color(0xFF0F1722)
private val Navy800 = Color(0xFF1A2332)
private val Navy700 = Color(0xFF1E2A3A)
private val Navy600 = Color(0xFF26344A)
private val Navy500 = Color(0xFF2D3A4E)
private val Blue300 = Color(0xFFA7C7FF)
private val Blue900 = Color(0xFF0A0F2C)
private val Blue800 = Color(0xFF1E3A8A)
private val Blue200 = Color(0xFFD9E7FF)
private val Mint200 = Color(0xFF9FFFE0)
private val Sky300 = Color(0xFFB8D8FF)
private val Sky900 = Color(0xFF102A43)
private val Sky800 = Color(0xFF284B70)
private val Sky200 = Color(0xFFD7EBFF)
private val Amber300 = Color(0xFFF5C36B)
private val Amber900 = Color(0xFF432C00)
private val Amber800 = Color(0xFF5F4100)
private val Amber200 = Color(0xFFFFE1A3)
private val Paper = Color(0xFFF5FBF8)
private val PaperContainer = Color(0xFFE9EFEC)

private val DarkColors = darkColorScheme(
    primary = Blue300,
    onPrimary = Blue900,
    primaryContainer = Blue800,
    onPrimaryContainer = Blue200,
    secondary = Sky300,
    onSecondary = Sky900,
    secondaryContainer = Sky800,
    onSecondaryContainer = Sky200,
    tertiary = Amber300,
    onTertiary = Amber900,
    tertiaryContainer = Amber800,
    onTertiaryContainer = Amber200,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Navy900,
    onBackground = Color(0xFFF7F9FB),
    surface = Navy900,
    onSurface = Color(0xFFF7F9FB),
    surfaceVariant = Navy500,
    onSurfaceVariant = Color(0xFFC8DCEE),
    surfaceTint = Blue300,
    inverseSurface = Color(0xFFE7F0FF),
    inverseOnSurface = Blue900,
    outline = Color(0xFF71809A),
    outlineVariant = Navy500,
    surfaceBright = Navy600,
    surfaceDim = Navy950,
    surfaceContainerLowest = Navy950,
    surfaceContainerLow = Navy800,
    surfaceContainer = Navy700,
    surfaceContainerHigh = Navy600,
    surfaceContainerHighest = Navy500,
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
