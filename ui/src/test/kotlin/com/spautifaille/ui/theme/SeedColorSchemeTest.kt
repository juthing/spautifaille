package com.spautifaille.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct
import com.spautifaille.domain.model.ColorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SeedColorSchemeTest {

    private val red = 0xFFD32F2F.toInt()
    private val blue = 0xFF1976D2.toInt()

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

    private fun hueDistance(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }

    private fun contrast(a: Color, b: Color): Float {
        val hi = maxOf(a.luminance(), b.luminance())
        val lo = minOf(a.luminance(), b.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }

    @Test
    fun `chaque role de couleur est genere et opaque`() {
        for (dark in listOf(false, true)) {
            val colors = seedColorScheme(red, dark).allColors()
            assertEquals(48, colors.size)
            assertTrue(colors.all { it.alpha == 1f })
        }
    }

    @Test
    fun `le schema clair et le schema sombre different`() {
        val schemes = seedSchemes(blue)
        assertNotEquals(schemes.light.surface, schemes.dark.surface)
        assertNotEquals(schemes.light.primary, schemes.dark.primary)
        assertEquals(schemes.light, schemes.forMode(dark = false))
        assertEquals(schemes.dark, schemes.forMode(dark = true))
    }

    @Test
    fun `l accent primary garde la teinte de la couleur source`() {
        val seedHue = Hct.fromInt(red).hue
        for (dark in listOf(false, true)) {
            val primaryHue = Hct.fromInt(seedColorScheme(red, dark).primary.toArgb()).hue
            assertTrue("teinte $primaryHue loin de $seedHue", hueDistance(seedHue, primaryHue) < 15)
        }
    }

    @Test
    fun `deux sources differentes donnent deux schemas differents`() {
        assertNotEquals(seedColorScheme(red, dark = true).primary, seedColorScheme(blue, dark = true).primary)
        assertNotEquals(
            seedColorScheme(red, dark = false).surfaceContainer,
            seedColorScheme(blue, dark = false).surfaceContainer,
        )
    }

    @Test
    fun `le texte reste lisible sur les surfaces`() {
        for (dark in listOf(false, true)) {
            val scheme = seedColorScheme(red, dark)
            assertTrue(contrast(scheme.onSurface, scheme.surface) >= 7f)
            assertTrue(contrast(scheme.onPrimary, scheme.primary) >= 4.5f)
            assertTrue(contrast(scheme.onPrimaryContainer, scheme.primaryContainer) >= 4.5f)
        }
    }

    @Test
    fun `la couleur source d une image majoritairement rouge est rouge`() {
        // 80 % rouge vif, 20 % gris : Score privilégie la teinte saturée et bien représentée.
        val pixels = IntArray(1000) { if (it < 800) red else 0xFF808080.toInt() }
        val seed = seedColorFromPixels(pixels)
        assertNotNull(seed)
        assertTrue(hueDistance(Hct.fromInt(red).hue, Hct.fromInt(seed!!).hue) < 10)
    }

    @Test
    fun `une image presque grise retombe sur la couleur dominante`() {
        val gray = 0xFF777777.toInt()
        val pixels = IntArray(1000) { if (it < 900) gray else 0xFF222222.toInt() }
        val seed = seedColorFromPixels(pixels)
        assertNotNull(seed)
        assertTrue(Hct.fromInt(seed!!).chroma < 5)
        // Un schéma neutre est quand même généré.
        assertEquals(48, seedColorScheme(seed, dark = true).allColors().size)
    }

    @Test
    fun `une image vide ou transparente ne donne pas de couleur source`() {
        assertNull(seedColorFromPixels(IntArray(0)))
        assertNull(seedColorFromPixels(IntArray(100) { 0x00000000 }))
    }

    @Test
    fun `la palette suit le reglage quand le theme dynamique est disponible`() {
        assertEquals(ResolvedPalette.STATIC, resolvePalette(ColorSource.STATIC, dynamicSupported = true, hasNowPlayingScheme = true))
        assertEquals(ResolvedPalette.DYNAMIC, resolvePalette(ColorSource.DYNAMIC, dynamicSupported = true, hasNowPlayingScheme = true))
        assertEquals(
            ResolvedPalette.NOW_PLAYING,
            resolvePalette(ColorSource.NOW_PLAYING, dynamicSupported = true, hasNowPlayingScheme = true),
        )
    }

    @Test
    fun `sous Android 11 le dynamique retombe sur le theme normal`() {
        assertEquals(ResolvedPalette.STATIC, resolvePalette(ColorSource.DYNAMIC, dynamicSupported = false, hasNowPlayingScheme = false))
    }

    @Test
    fun `sans musique en cours le theme musique en cours se replie sur dynamique puis normal`() {
        assertEquals(
            ResolvedPalette.DYNAMIC,
            resolvePalette(ColorSource.NOW_PLAYING, dynamicSupported = true, hasNowPlayingScheme = false),
        )
        assertEquals(
            ResolvedPalette.STATIC,
            resolvePalette(ColorSource.NOW_PLAYING, dynamicSupported = false, hasNowPlayingScheme = false),
        )
        assertEquals(
            ResolvedPalette.NOW_PLAYING,
            resolvePalette(ColorSource.NOW_PLAYING, dynamicSupported = false, hasNowPlayingScheme = true),
        )
    }
}
