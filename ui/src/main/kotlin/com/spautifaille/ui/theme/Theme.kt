package com.spautifaille.ui.theme

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.spautifaille.domain.model.ColorSource
import com.spautifaille.domain.model.ThemeMode

/** Durée de la transition douce entre deux schémas (changement de titre, de mode ou de source). */
private const val SCHEME_TRANSITION_MS = 600

/**
 * Thème de l'application, sans musique en cours : [dynamicColor] = couleurs du fond d'écran (Android 12+),
 * sinon le schéma statique. Raccourci utilisé par les aperçus et les tests ; l'application passe par la
 * variante à [ColorSource].
 */
@Composable
fun SpautifailleTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    SpautifailleTheme(
        themeMode = themeMode,
        colorSource = if (dynamicColor) ColorSource.DYNAMIC else ColorSource.STATIC,
        nowPlayingSchemes = null,
        content = content,
    )
}

/**
 * Thème de l'application. [colorSource] choisit l'origine des couleurs (voir [resolvePalette]) ; [nowPlayingSchemes]
 * sont les schémas générés depuis la pochette en cours (`null` si rien ne joue : repli sur Dynamique / Normal).
 * [themeMode] force clair / sombre ou suit le système. Les changements de schéma sont animés.
 */
@Composable
internal fun SpautifailleTheme(
    themeMode: ThemeMode,
    colorSource: ColorSource,
    nowPlayingSchemes: SeedSchemes?,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val target: ColorScheme = when (resolvePalette(colorSource, dynamicSupported, nowPlayingSchemes != null)) {
        ResolvedPalette.NOW_PLAYING -> nowPlayingSchemes?.forMode(darkTheme) ?: staticScheme(darkTheme)
        ResolvedPalette.DYNAMIC ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) systemDynamicScheme(context, darkTheme) else staticScheme(darkTheme)
        ResolvedPalette.STATIC -> staticScheme(darkTheme)
    }
    val colorScheme = animateColorScheme(target)

    // Edge-to-edge : l'activité gère le mode (enableEdgeToEdge), on aligne juste la teinte des icônes
    // des barres système sur le thème choisi dans l'app (qui peut différer du thème système).
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SpautifailleTypography,
        shapes = SpautifailleShapes,
        content = content,
    )
}

@RequiresApi(Build.VERSION_CODES.S)
private fun systemDynamicScheme(context: Context, dark: Boolean): ColorScheme =
    if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

private fun staticScheme(dark: Boolean): ColorScheme = if (dark) DarkColors else LightColors

/** Anime chaque rôle de couleur vers [target] : les surfaces et accents glissent d'un schéma à l'autre. */
@Composable
private fun animateColorScheme(target: ColorScheme): ColorScheme {
    val spec = tween<Color>(SCHEME_TRANSITION_MS)

    @Composable
    fun Color.animated(label: String): Color = animateColorAsState(this, spec, label = label).value

    return ColorScheme(
        primary = target.primary.animated("primary"),
        onPrimary = target.onPrimary.animated("onPrimary"),
        primaryContainer = target.primaryContainer.animated("primaryContainer"),
        onPrimaryContainer = target.onPrimaryContainer.animated("onPrimaryContainer"),
        inversePrimary = target.inversePrimary.animated("inversePrimary"),
        secondary = target.secondary.animated("secondary"),
        onSecondary = target.onSecondary.animated("onSecondary"),
        secondaryContainer = target.secondaryContainer.animated("secondaryContainer"),
        onSecondaryContainer = target.onSecondaryContainer.animated("onSecondaryContainer"),
        tertiary = target.tertiary.animated("tertiary"),
        onTertiary = target.onTertiary.animated("onTertiary"),
        tertiaryContainer = target.tertiaryContainer.animated("tertiaryContainer"),
        onTertiaryContainer = target.onTertiaryContainer.animated("onTertiaryContainer"),
        background = target.background.animated("background"),
        onBackground = target.onBackground.animated("onBackground"),
        surface = target.surface.animated("surface"),
        onSurface = target.onSurface.animated("onSurface"),
        surfaceVariant = target.surfaceVariant.animated("surfaceVariant"),
        onSurfaceVariant = target.onSurfaceVariant.animated("onSurfaceVariant"),
        surfaceTint = target.surfaceTint.animated("surfaceTint"),
        inverseSurface = target.inverseSurface.animated("inverseSurface"),
        inverseOnSurface = target.inverseOnSurface.animated("inverseOnSurface"),
        error = target.error.animated("error"),
        onError = target.onError.animated("onError"),
        errorContainer = target.errorContainer.animated("errorContainer"),
        onErrorContainer = target.onErrorContainer.animated("onErrorContainer"),
        outline = target.outline.animated("outline"),
        outlineVariant = target.outlineVariant.animated("outlineVariant"),
        scrim = target.scrim.animated("scrim"),
        surfaceBright = target.surfaceBright.animated("surfaceBright"),
        surfaceDim = target.surfaceDim.animated("surfaceDim"),
        surfaceContainer = target.surfaceContainer.animated("surfaceContainer"),
        surfaceContainerHigh = target.surfaceContainerHigh.animated("surfaceContainerHigh"),
        surfaceContainerHighest = target.surfaceContainerHighest.animated("surfaceContainerHighest"),
        surfaceContainerLow = target.surfaceContainerLow.animated("surfaceContainerLow"),
        surfaceContainerLowest = target.surfaceContainerLowest.animated("surfaceContainerLowest"),
        primaryFixed = target.primaryFixed.animated("primaryFixed"),
        primaryFixedDim = target.primaryFixedDim.animated("primaryFixedDim"),
        onPrimaryFixed = target.onPrimaryFixed.animated("onPrimaryFixed"),
        onPrimaryFixedVariant = target.onPrimaryFixedVariant.animated("onPrimaryFixedVariant"),
        secondaryFixed = target.secondaryFixed.animated("secondaryFixed"),
        secondaryFixedDim = target.secondaryFixedDim.animated("secondaryFixedDim"),
        onSecondaryFixed = target.onSecondaryFixed.animated("onSecondaryFixed"),
        onSecondaryFixedVariant = target.onSecondaryFixedVariant.animated("onSecondaryFixedVariant"),
        tertiaryFixed = target.tertiaryFixed.animated("tertiaryFixed"),
        tertiaryFixedDim = target.tertiaryFixedDim.animated("tertiaryFixedDim"),
        onTertiaryFixed = target.onTertiaryFixed.animated("onTertiaryFixed"),
        onTertiaryFixedVariant = target.onTertiaryFixedVariant.animated("onTertiaryFixedVariant"),
    )
}
