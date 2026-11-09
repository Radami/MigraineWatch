package com.radami.migrainewatch.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Material 3's tonal-spot schemes for the brand terracotta (BrandTerracottaLight), the same
// kind Android builds from a wallpaper. Used wherever there is no wallpaper scheme: iOS, and
// Android before 12. Generated with Material's colour utilities; regenerate rather than
// hand-tune, so every pairing keeps its contrast.
//
// The neutral palettes, which every surface and card is drawn from, use a chroma of 2 rather
// than tonal spot's 6 and 8: at full strength terracotta tints the cards peach, where a
// wallpaper's blue only reads as a cool grey.

internal val BrandLightColorScheme = lightColorScheme(
    primary = Color(0xFF8F4C33),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBCF),
    onPrimaryContainer = Color(0xFF380D00),
    inversePrimary = Color(0xFFFFB59A),
    secondary = Color(0xFF77574C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDBCF),
    onSecondaryContainer = Color(0xFF2C160D),
    tertiary = Color(0xFF695E2F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF2E3A8),
    onTertiaryContainer = Color(0xFF211B00),
    background = Color(0xFFFFF8F6),
    onBackground = Color(0xFF1E1B1A),
    surface = Color(0xFFFFF8F6),
    onSurface = Color(0xFF1E1B1A),
    surfaceVariant = Color(0xFFE9E1DF),
    onSurfaceVariant = Color(0xFF4A4644),
    surfaceTint = Color(0xFF8F4C33),
    inverseSurface = Color(0xFF33302E),
    inverseOnSurface = Color(0xFFF7EFED),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF7C7674),
    outlineVariant = Color(0xFFCCC5C3),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFF8F6),
    surfaceContainer = Color(0xFFF4ECEA),
    surfaceContainerHigh = Color(0xFFEFE6E4),
    surfaceContainerHighest = Color(0xFFE9E1DF),
    surfaceContainerLow = Color(0xFFFAF2F0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE0D8D6)
)

internal val BrandDarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFB59A),
    onPrimary = Color(0xFF55200A),
    primaryContainer = Color(0xFF71361E),
    onPrimaryContainer = Color(0xFFFFDBCF),
    inversePrimary = Color(0xFF8F4C33),
    secondary = Color(0xFFE7BEAF),
    onSecondary = Color(0xFF442A20),
    secondaryContainer = Color(0xFF5D4035),
    onSecondaryContainer = Color(0xFFFFDBCF),
    tertiary = Color(0xFFD5C78E),
    onTertiary = Color(0xFF383005),
    tertiaryContainer = Color(0xFF50471A),
    onTertiaryContainer = Color(0xFFF2E3A8),
    background = Color(0xFF161312),
    onBackground = Color(0xFFE9E1DF),
    surface = Color(0xFF161312),
    onSurface = Color(0xFFE9E1DF),
    surfaceVariant = Color(0xFF4A4644),
    onSurfaceVariant = Color(0xFFCCC5C3),
    surfaceTint = Color(0xFFFFB59A),
    inverseSurface = Color(0xFFE9E1DF),
    inverseOnSurface = Color(0xFF33302E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF968F8E),
    outlineVariant = Color(0xFF4A4644),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF3C3837),
    surfaceContainer = Color(0xFF221F1E),
    surfaceContainerHigh = Color(0xFF2D2928),
    surfaceContainerHighest = Color(0xFF383433),
    surfaceContainerLow = Color(0xFF1E1B1A),
    surfaceContainerLowest = Color(0xFF100E0D),
    surfaceDim = Color(0xFF161312)
)
