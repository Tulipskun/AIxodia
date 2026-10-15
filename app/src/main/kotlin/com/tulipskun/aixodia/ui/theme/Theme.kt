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

// The dark scheme's surfaces, all of them one navy at five strengths.
//
// The page is pure black and every surface above it is the old navy taken down
// to 55% (k = 0.55). That is the whole trick of the look: a surface only has to
// sit far enough above black to be seen, and the higher it sits the louder it
// reads — so this scale is a reading order, running from the well a code block
// sits in up to the pill resting on a card.
private val NavyRecess = Color(0xFF0B1220)
private val NavySunken = Color(0xFF0E131C)
private val NavyBase = Color(0xFF111720)
private val NavyCard = Color(0xFF151D29)
private val NavyPill = Color(0xFF19202B)
/** The hairline that keeps two darks meeting each other from reading as one. */
private val NavyLine = Color(0xFF2A3544)

/** The accent: icons, the cursor, the slash on the command button. */
private val Accent = Color(0xFF7FB2FF)

/** The user's own words, in a blue deep enough to read as the page turned. */
private val Bubble = Color(0xFF17305A)

private val Blue900 = Color(0xFF0A0F2C)
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
    primary = Accent,
    onPrimary = Blue900,
    primaryContainer = Bubble,
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
    background = Color(0xFF000000),
    onBackground = Color(0xFFF7F9FB),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF7F9FB),
    surfaceVariant = NavyPill,
    onSurfaceVariant = Color(0xFFC8DCEE),
    surfaceTint = Accent,
    inverseSurface = Color(0xFFE7F0FF),
    inverseOnSurface = Blue900,
    outline = Color(0xFF71809A),
    outlineVariant = NavyLine,
    surfaceBright = NavyCard,
    surfaceDim = NavyRecess,
    surfaceContainerLowest = NavyRecess,
    surfaceContainerLow = NavySunken,
    surfaceContainer = NavyBase,
    surfaceContainerHigh = NavyCard,
    surfaceContainerHighest = NavyPill,
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

/**
 * The app theme. Dark, with the corner radius that the app's own shapes are
 * built from. Nothing here is remote: the look is part of the app.
 */
@Composable
fun AIxodiaTheme(
    dark: Boolean = true,
    cornerRadius: Int = 12,
    primary: String = "",
    content: @Composable () -> Unit,
) {
    val base = if (dark) DarkColors else LightColors
    val accent = parseHexColor(primary)
    val scheme = if (accent == null) base else base.copy(primary = accent, surfaceTint = accent)
    val radius = cornerRadius.coerceIn(0, 32)
    val shapes = AppShapes.copy(
        small = RoundedCornerShape((radius * 0.55f).toInt().dp),
        medium = RoundedCornerShape(radius.dp),
        large = RoundedCornerShape((radius * 1.2f).toInt().dp),
        extraLarge = RoundedCornerShape((radius * 1.5f).toInt().dp),
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = AppTypography,
        shapes = shapes,
        content = content,
    )
}

/** Parses "#RRGGBB" (or "#AARRGGBB"), returning null when it is not a colour. */
fun parseHexColor(value: String): Color? {
    val hex = value.trim().removePrefix("#")
    if (hex.length != 6 && hex.length != 8) return null
    val long = hex.toLongOrNull(16) ?: return null
    return if (hex.length == 6) Color(0xFF000000L or long) else Color(long)
}
