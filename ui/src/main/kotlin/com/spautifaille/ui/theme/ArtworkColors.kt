package com.spautifaille.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs

/** Couleur en teinte / saturation / luminosité (tous entre 0 et 1, sauf [h] en degrés). */
internal data class Hsl(val h: Float, val s: Float, val l: Float)

internal fun Color.toHsl(): Hsl {
    val r = red
    val g = green
    val b = blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    val l = (max + min) / 2f
    if (d < 1e-6f) return Hsl(0f, 0f, l)
    val s = d / (1f - abs(2f * l - 1f))
    val h = when (max) {
        r -> ((g - b) / d).mod(6f)
        g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    } * 60f
    return Hsl(h, s.coerceIn(0f, 1f), l)
}

internal fun hslColor(h: Float, s: Float, l: Float): Color {
    val c = (1f - abs(2f * l - 1f)) * s
    val hp = (h / 60f).mod(6f)
    val x = c * (1f - abs(hp.mod(2f) - 1f))
    val (r1, g1, b1) = when {
        hp < 1f -> Triple(c, x, 0f)
        hp < 2f -> Triple(x, c, 0f)
        hp < 3f -> Triple(0f, c, x)
        hp < 4f -> Triple(0f, x, c)
        hp < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = l - c / 2f
    return Color(
        red = (r1 + m).coerceIn(0f, 1f),
        green = (g1 + m).coerceIn(0f, 1f),
        blue = (b1 + m).coerceIn(0f, 1f),
    )
}

/**
 * Couleurs dérivées de la pochette, **source de vérité unique** du lecteur plein écran et du thème « Musique en
 * cours » de l'application : l'accent (rôles `primary*`) et le haut du dégradé de fond du lecteur ([backgroundTop],
 * qui se fond dans `surface` vers le bas).
 */
@Immutable
internal data class ArtworkColors(
    val backgroundTop: Color,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
)

/**
 * Dérive les couleurs depuis [seed] (couleur dominante / vibrante de la pochette) : on garde la
 * teinte et on impose des luminosités proches des tons M3 (80 / 30 / 90 en sombre, 40 / 90 / 10 en clair)
 * afin que le texte reste lisible quelle que soit la pochette. Sans [seed], reprend le schéma du thème.
 */
internal fun deriveArtworkColors(seed: Color?, scheme: ColorScheme, dark: Boolean): ArtworkColors {
    if (seed == null) {
        return ArtworkColors(
            backgroundTop = scheme.surfaceContainerHigh,
            primary = scheme.primary,
            onPrimary = scheme.onPrimary,
            primaryContainer = scheme.primaryContainer,
            onPrimaryContainer = scheme.onPrimaryContainer,
        )
    }
    val hsl = seed.toHsl()
    val h = hsl.h
    // Pochette quasi grise : on reste neutre plutôt que de teinter artificiellement.
    val sat = if (hsl.s < NEUTRAL_SATURATION) hsl.s else hsl.s.coerceIn(MIN_SATURATION, MAX_SATURATION)
    // La luminance perçue dépend de la teinte (un jaune est bien plus clair qu'un bleu à luminosité HSL égale) :
    // chaque couleur part d'une luminosité cible puis est corrigée jusqu'au contraste requis.
    return if (dark) {
        val top = adjust(0.30f, -LIGHTNESS_STEP) { l -> lerp(scheme.surface, hslColor(h, sat * 0.8f, l), 0.8f) }
            .until { contrastRatio(scheme.onSurface, it) >= TEXT_CONTRAST }
        val onContainer = hslColor(h, sat, 0.90f)
        ArtworkColors(
            backgroundTop = top,
            primary = adjust(0.80f, LIGHTNESS_STEP) { l -> hslColor(h, sat, l) }
                .until { contrastRatio(it, top) >= UI_CONTRAST_DARK },
            onPrimary = hslColor(h, sat * 0.8f, 0.16f),
            primaryContainer = adjust(0.28f, -LIGHTNESS_STEP) { l -> hslColor(h, sat * 0.7f, l) }
                .until { contrastRatio(onContainer, it) >= UI_CONTRAST_DARK },
            onPrimaryContainer = onContainer,
        )
    } else {
        val top = adjust(0.80f, LIGHTNESS_STEP) { l -> lerp(scheme.surface, hslColor(h, sat, l), 0.85f) }
            .until { contrastRatio(scheme.onSurface, it) >= TEXT_CONTRAST }
        val onContainer = hslColor(h, sat, 0.12f)
        val onPrimary = hslColor(h, sat * 0.3f, 0.98f)
        ArtworkColors(
            backgroundTop = top,
            primary = adjust(0.36f, -LIGHTNESS_STEP) { l -> hslColor(h, sat, l) }
                .until { contrastRatio(onPrimary, it) >= UI_CONTRAST_DARK && contrastRatio(it, top) >= UI_CONTRAST_LIGHT },
            onPrimary = onPrimary,
            primaryContainer = adjust(0.88f, LIGHTNESS_STEP) { l -> hslColor(h, sat, l) }
                .until { contrastRatio(onContainer, it) >= UI_CONTRAST_DARK },
            onPrimaryContainer = onContainer,
        )
    }
}

/** Rapport de contraste WCAG entre deux couleurs opaques (1 à 21). */
internal fun contrastRatio(a: Color, b: Color): Float {
    val hi = maxOf(a.luminance(), b.luminance())
    val lo = minOf(a.luminance(), b.luminance())
    return (hi + 0.05f) / (lo + 0.05f)
}

/** Couleur candidate en fonction de la luminosité, à faire varier par pas jusqu'à satisfaire une condition. */
private class LightnessSearch(val start: Float, val step: Float, val make: (Float) -> Color)

private fun adjust(start: Float, step: Float, make: (Float) -> Color) = LightnessSearch(start, step, make)

private inline fun LightnessSearch.until(ok: (Color) -> Boolean): Color {
    var l = start
    var color = make(l)
    while (!ok(color) && l + step in 0f..1f) {
        l += step
        color = make(l)
    }
    return color
}

private const val LIGHTNESS_STEP = 0.01f
private const val TEXT_CONTRAST = 7f
private const val UI_CONTRAST_DARK = 4.5f
private const val UI_CONTRAST_LIGHT = 3f
private const val NEUTRAL_SATURATION = 0.12f
private const val MIN_SATURATION = 0.40f
private const val MAX_SATURATION = 0.85f
