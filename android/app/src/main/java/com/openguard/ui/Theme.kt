package com.openguard.ui

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

// OpenGuard design tokens. Three layers, following the design-system skill:
// primitives (raw brand values) -> semantic (light/dark color schemes) ->
// component (shapes, type). Screens reference the semantic layer only,
// never a raw hex value.
//
// Brand: the web dashboard's Midnight Navy + Champagne Gold + Soft Ivory.
// Radii follow the daisyUI scale (8dp fields/selectors, 16dp boxes);
// cards use a 1dp outline instead of a shadow (daisyUI depth 1).

// Primitives: navy
private val Navy900 = Color(0xFF101B2D)
private val Navy800 = Color(0xFF1A2332)
private val Navy700 = Color(0xFF192A40)
private val Navy600 = Color(0xFF22344A)
private val Navy500 = Color(0xFF344358)
private val Navy400 = Color(0xFF2A3B52)
private val NavyDeep = Color(0xFF0B1522)

// Primitives: blue (interactive)
private val Blue = Color(0xFF1A5FB4)
private val BlueLight = Color(0xFF7FB2E5)
private val BlueContainer = Color(0xFFDCE8F7)
private val BlueDeep = Color(0xFF0B2F57)
private val BlueDark = Color(0xFF1D3A5F)

// Primitives: champagne gold (accent, used sparingly)
private val Gold = Color(0xFFC6A46A)
private val GoldLight = Color(0xFFF5EBD9)
private val GoldDeep = Color(0xFF6B5322)
private val GoldDark = Color(0xFF4A3B22)

// Primitives: neutrals
private val Ivory = Color(0xFFF4F3EF)
private val Paper = Color(0xFFF7F8FA)
private val Mist = Color(0xFFEEF1F4)
private val MistLight = Color(0xFFF4F6F8)
private val MistDark = Color(0xFFE8ECF0)
private val Muted = Color(0xFF667085)
private val MutedDark = Color(0xFFA7B2C2)
private val Line = Color(0xFFDDE3EA)
private val LineSoft = Color(0xFFE8ECF0)

// Primitives: green (positive states) and red (errors, danger)
private val Green = Color(0xFF1B7A4D)
private val GreenLight = Color(0xFF6FCF97)
private val GreenContainer = Color(0xFFDDF2E5)
private val GreenDeep = Color(0xFF0D4429)
private val GreenDark = Color(0xFF14532D)
private val Red = Color(0xFFB3261E)
private val RedLight = Color(0xFFF2B8B5)
private val RedContainer = Color(0xFFFCE8E6)
private val RedDeep = Color(0xFF601410)
private val RedDark = Color(0xFF7A1F1A)

// Semantic: light scheme
private val LightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = BlueContainer,
    onPrimaryContainer = BlueDeep,
    inversePrimary = BlueLight,
    secondary = Gold,
    onSecondary = Navy800,
    secondaryContainer = GoldLight,
    onSecondaryContainer = GoldDeep,
    tertiary = Green,
    onTertiary = Color.White,
    tertiaryContainer = GreenContainer,
    onTertiaryContainer = GreenDeep,
    background = Paper,
    onBackground = Navy800,
    surface = Color.White,
    onSurface = Navy800,
    surfaceVariant = Mist,
    onSurfaceVariant = Muted,
    inverseSurface = Navy800,
    inverseOnSurface = Ivory,
    error = Red,
    onError = Color.White,
    errorContainer = RedContainer,
    onErrorContainer = RedDeep,
    outline = Line,
    outlineVariant = LineSoft,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFE3E7EB),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = MistLight,
    surfaceContainer = Mist,
    surfaceContainerHigh = MistDark,
    surfaceContainerHighest = Line,
)

// Semantic: dark scheme (midnight navy surfaces)
private val DarkColors = darkColorScheme(
    primary = BlueLight,
    onPrimary = BlueDeep,
    primaryContainer = BlueDark,
    onPrimaryContainer = BlueContainer,
    inversePrimary = Blue,
    secondary = Gold,
    onSecondary = Navy800,
    secondaryContainer = GoldDark,
    onSecondaryContainer = GoldLight,
    tertiary = GreenLight,
    onTertiary = GreenDeep,
    tertiaryContainer = GreenDark,
    onTertiaryContainer = GreenContainer,
    background = Navy900,
    onBackground = Ivory,
    surface = Navy700,
    onSurface = Ivory,
    surfaceVariant = Navy600,
    onSurfaceVariant = MutedDark,
    inverseSurface = Ivory,
    inverseOnSurface = Navy800,
    error = RedLight,
    onError = RedDeep,
    errorContainer = RedDark,
    onErrorContainer = RedContainer,
    outline = Navy500,
    outlineVariant = Navy400,
    surfaceBright = Navy600,
    surfaceDim = NavyDeep,
    surfaceContainerLowest = NavyDeep,
    surfaceContainerLow = Navy900,
    surfaceContainer = Navy700,
    surfaceContainerHigh = Navy600,
    surfaceContainerHighest = Navy500,
)

// Component: shapes (daisyUI radius scale, in dp)
private val OpenGuardShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

// Component: type scale. Headings semibold for a clear hierarchy;
// body text 16/14sp with 1.5 line height.
private val OpenGuardTypography = Typography(
    headlineMedium = TextStyle(
        fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold,
    ),
    headlineSmall = TextStyle(
        fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold,
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold,
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(
        fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium,
    ),
)

@Composable
fun OpenGuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = OpenGuardShapes,
        typography = OpenGuardTypography,
        content = content,
    )
}
