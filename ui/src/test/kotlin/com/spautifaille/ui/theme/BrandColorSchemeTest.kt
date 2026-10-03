package com.spautifaille.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Thème « Normal » : schémas générés depuis l'orange de l'icône (#E34211). */
class BrandColorSchemeTest {

    private val seedHct = Hct.fromInt(0xFFE34211.toInt())

    private fun ColorScheme.allColors(): List<Color> = listOf(
        primary, onPrimary, primaryContainer, onPrimaryContainer, inversePrimary,
        secondary, onSecondary, secondaryContainer, onSecondaryContainer,
        tertiary, onTertiary, tertiaryContainer, onTertiaryContainer,
        background, onBackground, surface, onSurface, surfaceVariant, onSurfaceVariant, surfaceTint,
        inverseSurface, inverseOnSurface, error, onError, errorContainer, onErrorContainer,
        outline, outlineVariant, scrim, surfaceBright, surfaceDim,
        surfaceContainerLowest, surfaceContainerLow, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest,
        primaryFixed, primaryFixedDim, onPrimaryFixed, onPrimaryFixedVariant,
        secondaryFixed, secondaryFixedDim, onSecondaryFixed, onSecondaryFixedVariant,
        tertiaryFixed, tertiaryFixedDim, onTertiaryFixed, onTertiaryFixedVariant,
    )

    @Test
    fun `la graine est l orange de l icone`() {
        assertEquals(Color(0xFFE34211), BrandSeed)
    }

    @Test
    fun `les palettes de l application viennent de la graine`() {
        assertEquals(brandColorScheme(BrandSeed, dark = false).allColors(), LightColors.allColors())
        assertEquals(brandColorScheme(BrandSeed, dark = true).allColors(), DarkColors.allColors())
        assertNotEquals(LightColors.surface, DarkColors.surface)
    }

    @Test
    fun `chaque role de couleur est genere et opaque`() {
        for (scheme in listOf(LightColors, DarkColors)) {
            val colors = scheme.allColors()
            assertEquals(48, colors.size)
            assertTrue(colors.all { it.alpha == 1f })
        }
    }

    @Test
    fun `le primary clair reste l orange de l icone`() {
        val primary = Hct.fromInt(LightColors.primary.toArgb())
        val hueGap = abs(primary.hue - seedHct.hue)
        assertTrue("teinte ${primary.hue} loin de ${seedHct.hue}", hueGap < 6)
        assertTrue("chroma ${primary.chroma} trop désaturée (graine ${seedHct.chroma})", primary.chroma > seedHct.chroma * 0.9)
        assertTrue("ton ${primary.tone} trop éloigné de ${seedHct.tone}", abs(primary.tone - seedHct.tone) < 6)
    }

    @Test
    fun `le primary sombre garde la teinte de l icone`() {
        val primary = Hct.fromInt(DarkColors.primary.toArgb())
        assertTrue(abs(primary.hue - seedHct.hue) < 8)
        assertTrue("chroma ${primary.chroma}", primary.chroma > 50)
    }

    @Test
    fun `les contrastes M3 minimaux sont respectes en clair et en sombre`() {
        for ((name, scheme) in listOf("clair" to LightColors, "sombre" to DarkColors)) {
            fun check(label: String, on: Color, background: Color, min: Float) {
                val ratio = contrastRatio(on, background)
                assertTrue("$name : $label = $ratio < $min", ratio >= min)
            }
            check("onPrimary/primary", scheme.onPrimary, scheme.primary, 4.5f)
            check("onPrimaryContainer/primaryContainer", scheme.onPrimaryContainer, scheme.primaryContainer, 7f)
            check("onSecondary/secondary", scheme.onSecondary, scheme.secondary, 4.5f)
            check("onSecondaryContainer/secondaryContainer", scheme.onSecondaryContainer, scheme.secondaryContainer, 4.5f)
            check("onTertiary/tertiary", scheme.onTertiary, scheme.tertiary, 4.5f)
            check("onTertiaryContainer/tertiaryContainer", scheme.onTertiaryContainer, scheme.tertiaryContainer, 4.5f)
            check("onError/error", scheme.onError, scheme.error, 4.5f)
            check("onErrorContainer/errorContainer", scheme.onErrorContainer, scheme.errorContainer, 4.5f)
            check("onSurface/surface", scheme.onSurface, scheme.surface, 7f)
            check("onSurfaceVariant/surfaceVariant", scheme.onSurfaceVariant, scheme.surfaceVariant, 4.5f)
            check("onSurface/surfaceContainerHighest", scheme.onSurface, scheme.surfaceContainerHighest, 7f)
            check("primary/surface", scheme.primary, scheme.surface, 3f)
            check("inverseOnSurface/inverseSurface", scheme.inverseOnSurface, scheme.inverseSurface, 7f)
        }
    }

    @Test
    fun `l inversePrimary est l accent de l autre mode et surfaceTint suit primary`() {
        assertEquals(DarkColors.primary, LightColors.inversePrimary)
        assertEquals(LightColors.primary, DarkColors.inversePrimary)
        assertEquals(LightColors.primary, LightColors.surfaceTint)
        assertEquals(DarkColors.primary, DarkColors.surfaceTint)
    }
}
