package com.spautifaille.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct
import com.spautifaille.domain.model.ColorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SeedColorSchemeTest {

    private val red = Color(0xFFD32F2F)
    private val blue = Color(0xFF1976D2)
    private val seeds = listOf(
        red,
        blue,
        Color(0xFFFDD835), // jaune vif
        Color(0xFF43A047), // vert
        Color(0xFF8E24AA), // violet
        Color(0xFFFFFFFF), // blanc
        Color(0xFF000000), // noir
        Color(0xFF808080), // gris
    )

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

    // --- Schéma complet ---

    @Test
    fun `chaque role de couleur est genere et opaque`() {
        for (dark in listOf(false, true)) {
            for (seed in seeds) {
                val colors = nowPlayingColorScheme(seed, dark).allColors()
                assertEquals(48, colors.size)
                assertTrue("seed $seed", colors.all { it.alpha == 1f })
            }
        }
    }

    @Test
    fun `l application prend exactement les couleurs d accent du grand lecteur`() {
        for (dark in listOf(false, true)) {
            for (seed in seeds) {
                val app = nowPlayingColorScheme(seed, dark)
                // Le lecteur dérive ses couleurs depuis le même seed, sur le schéma de l'application (thème « Musique en cours »).
                val player = deriveArtworkColors(seed, app, dark)
                assertEquals("primary $seed", player.primary, app.primary)
                assertEquals("onPrimary $seed", player.onPrimary, app.onPrimary)
                assertEquals("primaryContainer $seed", player.primaryContainer, app.primaryContainer)
                assertEquals("onPrimaryContainer $seed", player.onPrimaryContainer, app.onPrimaryContainer)
                assertEquals(app.primary, app.surfaceTint)
            }
        }
    }

    @Test
    fun `le schema clair et le schema sombre different`() {
        val light = nowPlayingColorScheme(blue, dark = false)
        val dark = nowPlayingColorScheme(blue, dark = true)
        assertNotEquals(light.surface, dark.surface)
        assertNotEquals(light.primary, dark.primary)
    }

    @Test
    fun `l accent garde la teinte de la couleur source`() {
        val seedHue = Hct.fromInt(red.toArgb()).hue
        for (dark in listOf(false, true)) {
            val primaryHue = Hct.fromInt(nowPlayingColorScheme(red, dark).primary.toArgb()).hue
            assertTrue("teinte $primaryHue loin de $seedHue", hueDistance(seedHue, primaryHue) < 20)
        }
    }

    @Test
    fun `deux sources differentes donnent deux schemas differents`() {
        assertNotEquals(nowPlayingColorScheme(red, dark = true).primary, nowPlayingColorScheme(blue, dark = true).primary)
        assertNotEquals(
            nowPlayingColorScheme(red, dark = false).surfaceContainer,
            nowPlayingColorScheme(blue, dark = false).surfaceContainer,
        )
        assertNotEquals(nowPlayingColorScheme(red, dark = false).secondary, nowPlayingColorScheme(blue, dark = false).secondary)
    }

    @Test
    fun `le texte et l accent restent lisibles sur les surfaces`() {
        for (dark in listOf(false, true)) {
            for (seed in seeds) {
                val scheme = nowPlayingColorScheme(seed, dark)
                val tag = "seed $seed dark=$dark"
                assertTrue(tag, contrastRatio(scheme.onSurface, scheme.surface) >= 7f)
                assertTrue(tag, contrastRatio(scheme.onPrimary, scheme.primary) >= 4.5f)
                assertTrue(tag, contrastRatio(scheme.onPrimaryContainer, scheme.primaryContainer) >= 4.5f)
                assertTrue(tag, contrastRatio(scheme.onSecondaryContainer, scheme.secondaryContainer) >= 4.5f)
                assertTrue("accent sur surface, $tag", contrastRatio(scheme.primary, scheme.surface) >= 3f)
            }
        }
    }

    @Test
    fun `une pochette grise donne un schema neutre complet`() {
        val scheme = nowPlayingColorScheme(Color(0xFF777777), dark = true)
        assertEquals(48, scheme.allColors().size)
        assertTrue(Hct.fromInt(scheme.primary.toArgb()).chroma < 20)
    }

    // --- Palette appliquée ---

    @Test
    fun `la palette suit le reglage quand le theme dynamique est disponible`() {
        assertEquals(ResolvedPalette.STATIC, resolvePalette(ColorSource.STATIC, dynamicSupported = true, hasNowPlayingSeed = true))
        assertEquals(ResolvedPalette.DYNAMIC, resolvePalette(ColorSource.DYNAMIC, dynamicSupported = true, hasNowPlayingSeed = true))
        assertEquals(
            ResolvedPalette.NOW_PLAYING,
            resolvePalette(ColorSource.NOW_PLAYING, dynamicSupported = true, hasNowPlayingSeed = true),
        )
    }

    @Test
    fun `sous Android 11 le dynamique retombe sur le theme normal`() {
        assertEquals(ResolvedPalette.STATIC, resolvePalette(ColorSource.DYNAMIC, dynamicSupported = false, hasNowPlayingSeed = false))
    }

    @Test
    fun `sans musique en cours le theme musique en cours se replie sur dynamique puis normal`() {
        assertEquals(
            ResolvedPalette.DYNAMIC,
            resolvePalette(ColorSource.NOW_PLAYING, dynamicSupported = true, hasNowPlayingSeed = false),
        )
        assertEquals(
            ResolvedPalette.STATIC,
            resolvePalette(ColorSource.NOW_PLAYING, dynamicSupported = false, hasNowPlayingSeed = false),
        )
        assertEquals(
            ResolvedPalette.NOW_PLAYING,
            resolvePalette(ColorSource.NOW_PLAYING, dynamicSupported = false, hasNowPlayingSeed = true),
        )
    }
}
