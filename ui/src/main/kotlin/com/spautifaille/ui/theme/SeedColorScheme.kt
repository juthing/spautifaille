package com.spautifaille.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.materialkolor.dynamiccolor.DynamicColor
import com.materialkolor.dynamiccolor.MaterialDynamicColors
import com.materialkolor.hct.Hct
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.score.Score
import com.spautifaille.domain.model.ColorSource

/*
 * Génération d'un schéma Material 3 complet à partir d'une couleur source (thème « Musique en cours »).
 * Algorithme officiel de Material (HCT) via `material-color-utilities` : quantification Celebi + Score pour
 * choisir la couleur source dans la pochette, puis `SchemeTonalSpot` pour tous les rôles de couleur.
 * Logique pure (aucune dépendance Android) : testable en JVM.
 */

/** Nombre de couleurs conservées par la quantification. */
private const val QUANTIZER_MAX_COLORS = 128

/** Schémas clair et sombre générés depuis une même couleur [seed] (ARGB). */
@Immutable
internal data class SeedSchemes(val seed: Int, val light: ColorScheme, val dark: ColorScheme) {
    fun forMode(dark: Boolean): ColorScheme = if (dark) this.dark else light
}

/** Génère les deux schémas à partir de [seed]. */
internal fun seedSchemes(seed: Int): SeedSchemes =
    SeedSchemes(seed = seed, light = seedColorScheme(seed, dark = false), dark = seedColorScheme(seed, dark = true))

/**
 * Choisit la couleur source d'une image à partir de ses [pixels] (ARGB) : quantification Celebi puis `Score`
 * (privilégie les teintes saturées et bien représentées). Pour une image presque grise, que `Score` écarte,
 * retombe sur la couleur la plus fréquente afin de produire un schéma neutre plutôt que rien. `null` si
 * [pixels] est vide ou entièrement transparent.
 */
internal fun seedColorFromPixels(pixels: IntArray): Int? {
    val opaque = pixels.filter { (it ushr ALPHA_SHIFT) == OPAQUE }.toIntArray()
    if (opaque.isEmpty()) return null
    val quantized = QuantizerCelebi.quantize(opaque, QUANTIZER_MAX_COLORS)
    if (quantized.isEmpty()) return null
    Score.score(quantized, desired = 1, fallbackColorArgb = null).firstOrNull()?.let { return it }
    return quantized.maxByOrNull { it.value }?.key
}

private const val ALPHA_SHIFT = 24
private const val OPAQUE = 0xFF

/** Schéma M3 « tonal spot » (celui de Material You) de couleur source [seedArgb]. */
internal fun seedColorScheme(seedArgb: Int, dark: Boolean): ColorScheme {
    val scheme = SchemeTonalSpot(sourceColorHct = Hct.fromInt(seedArgb), isDark = dark, contrastLevel = 0.0)
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
 * Choisit la palette à appliquer. « Musique en cours » sans schéma disponible (rien ne joue, pochette absente
 * ou illisible) retombe sur le thème dynamique si l'appareil le permet (Android 12+), sinon sur le thème normal.
 */
internal fun resolvePalette(
    source: ColorSource,
    dynamicSupported: Boolean,
    hasNowPlayingScheme: Boolean,
): ResolvedPalette = when (source) {
    ColorSource.STATIC -> ResolvedPalette.STATIC
    ColorSource.DYNAMIC ->
        if (dynamicSupported) ResolvedPalette.DYNAMIC else ResolvedPalette.STATIC
    ColorSource.NOW_PLAYING -> when {
        hasNowPlayingScheme -> ResolvedPalette.NOW_PLAYING
        dynamicSupported -> ResolvedPalette.DYNAMIC
        else -> ResolvedPalette.STATIC
    }
}
