package com.spautifaille.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.SchemeContent

/**
 * Schéma de marque (thème « Normal ») généré depuis la couleur de l'icône [seed], clair ou sombre. Variante « Content »
 * de Material (HCT) : elle conserve la saturation de la graine (TonalSpot la désaturerait) et teinte légèrement les
 * surfaces (crème en clair, brun profond en sombre), avec un `secondary` rouille et un `tertiary` doré analogues.
 * Les rôles `primary*` sont recalculés pour que `primary` reste l'orange de l'icône : même teinte et même chroma que
 * [seed], seul le ton est abaissé juste assez pour que le texte blanc dessus atteigne 4,5:1 (en sombre, ton fixe lisible
 * sur les surfaces brunes). `inversePrimary` est l'accent de l'autre mode, `surfaceTint` suit `primary`.
 */
internal fun brandColorScheme(seed: Color, dark: Boolean): ColorScheme {
    val source = Hct.fromInt(seed.toArgb())
    val base = materialColorScheme(SchemeContent(sourceColorHct = source, isDark = dark, contrastLevel = 0.0))
    val accent = brandAccent(source, dark)
    return base.copy(
        primary = accent.primary,
        onPrimary = accent.onPrimary,
        primaryContainer = accent.primaryContainer,
        onPrimaryContainer = accent.onPrimaryContainer,
        inversePrimary = brandAccent(source, !dark).primary,
        surfaceTint = accent.primary,
    )
}

private class BrandAccent(val primary: Color, val onPrimary: Color, val primaryContainer: Color, val onPrimaryContainer: Color)

private const val BRAND_TEXT_CONTRAST = 4.5f
private const val BRAND_DARK_PRIMARY_TONE = 68.0
private const val BRAND_TONE_STEP = 0.5

private fun brandAccent(source: Hct, dark: Boolean): BrandAccent {
    fun tone(chroma: Double, tone: Double) = Color(Hct.from(source.hue, minOf(chroma, source.chroma), tone).toInt())
    if (dark) {
        return BrandAccent(
            primary = tone(source.chroma, BRAND_DARK_PRIMARY_TONE),
            onPrimary = tone(40.0, 15.0),
            primaryContainer = tone(60.0, 30.0),
            onPrimaryContainer = tone(40.0, 90.0),
        )
    }
    var primaryTone = source.tone
    var primary = tone(source.chroma, primaryTone)
    while (contrastRatio(Color.White, primary) < BRAND_TEXT_CONTRAST && primaryTone > BRAND_TONE_STEP) {
        primaryTone -= BRAND_TONE_STEP
        primary = tone(source.chroma, primaryTone)
    }
    return BrandAccent(
        primary = primary,
        onPrimary = Color.White,
        primaryContainer = tone(48.0, 90.0),
        onPrimaryContainer = tone(60.0, 10.0),
    )
}
