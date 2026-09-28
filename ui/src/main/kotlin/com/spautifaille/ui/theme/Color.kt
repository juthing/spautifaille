package com.spautifaille.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * Palettes de repli (Android < 12 ou couleurs dynamiques désactivées), générées à partir d'une graine
 * verte (#1DB954). Ce sont les seules couleurs en dur de l'app : tout le reste passe par MaterialTheme.
 */

internal val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF006D33),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF94F9AB),
    onPrimaryContainer = Color(0xFF00210B),
    inversePrimary = Color(0xFF78DC90),
    secondary = Color(0xFF4F6353),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD2E8D4),
    onSecondaryContainer = Color(0xFF0D1F13),
    tertiary = Color(0xFF39656E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBDEAF5),
    onTertiaryContainer = Color(0xFF001F25),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF6FBF3),
    onBackground = Color(0xFF181D18),
    surface = Color(0xFFF6FBF3),
    onSurface = Color(0xFF181D18),
    surfaceVariant = Color(0xFFDDE5DA),
    onSurfaceVariant = Color(0xFF414941),
    surfaceTint = Color(0xFF006D33),
    inverseSurface = Color(0xFF2D322D),
    inverseOnSurface = Color(0xFFEEF2EA),
    outline = Color(0xFF717971),
    outlineVariant = Color(0xFFC1C9BE),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF6FBF3),
    surfaceDim = Color(0xFFD7DCD4),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F5ED),
    surfaceContainer = Color(0xFFEBEFE7),
    surfaceContainerHigh = Color(0xFFE5EAE2),
    surfaceContainerHighest = Color(0xFFDFE4DC),
)

internal val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF78DC90),
    onPrimary = Color(0xFF00391A),
    primaryContainer = Color(0xFF00522A),
    onPrimaryContainer = Color(0xFF94F9AB),
    inversePrimary = Color(0xFF006D33),
    secondary = Color(0xFFB6CCB8),
    onSecondary = Color(0xFF213527),
    secondaryContainer = Color(0xFF384B3C),
    onSecondaryContainer = Color(0xFFD2E8D4),
    tertiary = Color(0xFFA1CED9),
    onTertiary = Color(0xFF00363E),
    tertiaryContainer = Color(0xFF1F4D56),
    onTertiaryContainer = Color(0xFFBDEAF5),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101510),
    onBackground = Color(0xFFE0E4DC),
    surface = Color(0xFF101510),
    onSurface = Color(0xFFE0E4DC),
    surfaceVariant = Color(0xFF414941),
    onSurfaceVariant = Color(0xFFC1C9BE),
    surfaceTint = Color(0xFF78DC90),
    inverseSurface = Color(0xFFE0E4DC),
    inverseOnSurface = Color(0xFF2D322D),
    outline = Color(0xFF8B9389),
    outlineVariant = Color(0xFF414941),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF353B35),
    surfaceDim = Color(0xFF101510),
    surfaceContainerLowest = Color(0xFF0B0F0B),
    surfaceContainerLow = Color(0xFF181D18),
    surfaceContainer = Color(0xFF1C211C),
    surfaceContainerHigh = Color(0xFF272C26),
    surfaceContainerHighest = Color(0xFF323731),
)
