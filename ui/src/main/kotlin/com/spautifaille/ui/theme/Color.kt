package com.spautifaille.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/*
 * Palettes du thème « Normal » (« Les couleurs de Spautifaille » : Android < 12, ou couleurs dynamiques désactivées).
 * Elles sont générées au chargement par `brandColorScheme` (voir SeedColorScheme.kt) depuis une seule graine, l'orange
 * de l'icône de l'application (#E34211) : changer la marque = changer [BrandSeed]. Générer plutôt que figer les
 * 2 x 38 valeurs évite toute dérive entre la graine et les rôles, et le calcul (quelques dizaines de rôles HCT) est
 * négligeable. C'est la seule couleur de marque en dur de l'app : tout le reste passe par MaterialTheme.
 */

/** Orange de l'icône de l'application. */
internal val BrandSeed = Color(0xFFE34211)

internal val LightColors: ColorScheme = brandColorScheme(BrandSeed, dark = false)

internal val DarkColors: ColorScheme = brandColorScheme(BrandSeed, dark = true)
