package com.spautifaille.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.dynamiccolor.DynamicColor
import com.materialkolor.dynamiccolor.MaterialDynamicColors
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeTonalSpot
import com.spautifaille.domain.model.ColorSource

/*
 * Schéma M3 du thème « Musique en cours » : il est bâti sur la couleur source extraite de la pochette par
 * `rememberArtworkSeedColor` (la même que celle du lecteur plein écran). Les rôles d'accent `primary*` viennent de
 * `deriveArtworkColors` (exactement ceux du grand lecteur) ; tout le reste (surfaces, secondary, tertiary, erreurs,
 * contours...) est la palette « tonal spot » de Material (HCT, `material-color-utilities`) de la même couleur source,
 * pour que l'application entière reste cohérente et lisible en clair comme en sombre.
 * Logique pure (aucune dépendance Android) : testable en JVM.
 */

/**
 * Schéma de l'application pour la couleur source [seed] (pochette en cours), clair ou sombre. Ses rôles `primary`,
 * `onPrimary`, `primaryContainer` et `onPrimaryContainer` sont ceux que le lecteur plein écran affiche pour [seed] ;
 * `surfaceTint` suit `primary` (teinte d'élévation des surfaces).
 */
internal fun nowPlayingColorScheme(seed: Color, dark: Boolean): ColorScheme {
    val base = tonalSpotColorScheme(seed.toArgb(), dark)
    val accent = deriveArtworkColors(seed, base, dark)
    return base.copy(
        primary = accent.primary,
        onPrimary = accent.onPrimary,
        primaryContainer = accent.primaryContainer,
        onPrimaryContainer = accent.onPrimaryContainer,
        surfaceTint = accent.primary,
    )
}

/** Schéma M3 « tonal spot » (celui de Material You) de couleur source [seedArgb]. */
internal fun tonalSpotColorScheme(seedArgb: Int, dark: Boolean): ColorScheme =
    materialColorScheme(SchemeTonalSpot(sourceColorHct = Hct.fromInt(seedArgb), isDark = dark, contrastLevel = 0.0))

/** Tous les rôles Material 3 de [scheme] (HCT), en `ColorScheme` Compose. */
internal fun materialColorScheme(scheme: DynamicScheme): ColorScheme {
    val roles = MaterialDynamicColors()
    fun DynamicColor.color(): Color = Color(getArgb(scheme))
    // Tous les rôles sont fournis : les valeurs par défaut de light/darkColorScheme ne servent pas.
    return lightColorScheme(
        primary = roles.primary().color(),
        onPrimary = roles.onPrimary().color(),
        primaryContainer = roles.primaryContainer().color(),
        onPrimaryContainer = roles.onPrimaryContainer().color(),
        inversePrimary = roles.inversePrimary().color(),
        secondary = roles.secondary().color(),
        onSecondary = roles.onSecondary().color(),
        secondaryContainer = roles.secondaryContainer().color(),
        onSecondaryContainer = roles.onSecondaryContainer().color(),
        tertiary = roles.tertiary().color(),
        onTertiary = roles.onTertiary().color(),
        tertiaryContainer = roles.tertiaryContainer().color(),
        onTertiaryContainer = roles.onTertiaryContainer().color(),
        background = roles.background().color(),
        onBackground = roles.onBackground().color(),
        surface = roles.surface().color(),
        onSurface = roles.onSurface().color(),
        surfaceVariant = roles.surfaceVariant().color(),
        onSurfaceVariant = roles.onSurfaceVariant().color(),
        surfaceTint = roles.surfaceTint().color(),
        inverseSurface = roles.inverseSurface().color(),
        inverseOnSurface = roles.inverseOnSurface().color(),
        error = roles.error().color(),
        onError = roles.onError().color(),
        errorContainer = roles.errorContainer().color(),
        onErrorContainer = roles.onErrorContainer().color(),
        outline = roles.outline().color(),
        outlineVariant = roles.outlineVariant().color(),
        scrim = roles.scrim().color(),
        surfaceBright = roles.surfaceBright().color(),
        surfaceDim = roles.surfaceDim().color(),
        surfaceContainer = roles.surfaceContainer().color(),
        surfaceContainerHigh = roles.surfaceContainerHigh().color(),
        surfaceContainerHighest = roles.surfaceContainerHighest().color(),
        surfaceContainerLow = roles.surfaceContainerLow().color(),
        surfaceContainerLowest = roles.surfaceContainerLowest().color(),
        primaryFixed = roles.primaryFixed().color(),
        primaryFixedDim = roles.primaryFixedDim().color(),
        onPrimaryFixed = roles.onPrimaryFixed().color(),
        onPrimaryFixedVariant = roles.onPrimaryFixedVariant().color(),
        secondaryFixed = roles.secondaryFixed().color(),
        secondaryFixedDim = roles.secondaryFixedDim().color(),
        onSecondaryFixed = roles.onSecondaryFixed().color(),
        onSecondaryFixedVariant = roles.onSecondaryFixedVariant().color(),
        tertiaryFixed = roles.tertiaryFixed().color(),
        tertiaryFixedDim = roles.tertiaryFixedDim().color(),
        onTertiaryFixed = roles.onTertiaryFixed().color(),
        onTertiaryFixedVariant = roles.onTertiaryFixedVariant().color(),
    )
}

/** Palette effectivement appliquée, une fois le réglage confronté aux capacités de l'appareil et à la lecture. */
internal enum class ResolvedPalette { STATIC, DYNAMIC, NOW_PLAYING }

/**
 * Choisit la palette à appliquer. « Musique en cours » sans couleur source (rien ne joue, pochette absente ou
 * illisible) retombe sur le thème dynamique si l'appareil le permet (Android 12+), sinon sur le thème normal.
 */
internal fun resolvePalette(
    source: ColorSource,
    dynamicSupported: Boolean,
    hasNowPlayingSeed: Boolean,
): ResolvedPalette = when (source) {
    ColorSource.STATIC -> ResolvedPalette.STATIC
    ColorSource.DYNAMIC ->
        if (dynamicSupported) ResolvedPalette.DYNAMIC else ResolvedPalette.STATIC
    ColorSource.NOW_PLAYING -> when {
        hasNowPlayingSeed -> ResolvedPalette.NOW_PLAYING
        dynamicSupported -> ResolvedPalette.DYNAMIC
        else -> ResolvedPalette.STATIC
    }
}
