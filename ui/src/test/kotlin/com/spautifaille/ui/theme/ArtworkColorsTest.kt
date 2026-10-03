package com.spautifaille.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ArtworkColorsTest {
    private val light = lightColorScheme()
    private val dark = darkColorScheme()

    private val seeds = listOf(
        Color(0xFFE53935), // rouge
        Color(0xFFFDD835), // jaune vif
        Color(0xFF43A047), // vert
        Color(0xFF1E88E5), // bleu
        Color(0xFF8E24AA), // violet
        Color(0xFFFFFFFF), // blanc
        Color(0xFF000000), // noir
        Color(0xFF808080), // gris
    )

    private fun contrast(a: Color, b: Color) = contrastRatio(a, b)

    @Test
    fun `sans pochette on reprend le schema du theme`() {
        val colors = deriveArtworkColors(seed = null, scheme = light, dark = false)
        assertEquals(light.primary, colors.primary)
        assertEquals(light.onPrimary, colors.onPrimary)
        assertEquals(light.primaryContainer, colors.primaryContainer)
        assertEquals(light.surfaceContainerHigh, colors.backgroundTop)
    }

    @Test
    fun `la conversion HSL est reversible`() {
        seeds.forEach { seed ->
            val hsl = seed.toHsl()
            val back = hslColor(hsl.h, hsl.s, hsl.l)
            assertEquals(seed.red, back.red, 0.01f)
            assertEquals(seed.green, back.green, 0.01f)
            assertEquals(seed.blue, back.blue, 0.01f)
        }
    }

    @Test
    fun `la teinte de la pochette est conservee dans l'accent`() {
        val blue = Color(0xFF1E88E5)
        val hue = blue.toHsl().h
        listOf(true, false).forEach { isDark ->
            val colors = deriveArtworkColors(blue, if (isDark) dark else light, isDark)
            assertTrue(abs(colors.primary.toHsl().h - hue) < 5f)
        }
    }

    @Test
    fun `le texte reste lisible sur le fond et sur l'accent en theme sombre`() {
        seeds.forEach { seed ->
            val colors = deriveArtworkColors(seed, dark, dark = true)
            assertTrue("fond $seed", contrast(dark.onSurface, colors.backgroundTop) >= 7f)
            assertTrue("bouton $seed", contrast(colors.onPrimary, colors.primary) >= 4.5f)
            assertTrue("pastille $seed", contrast(colors.onPrimaryContainer, colors.primaryContainer) >= 4.5f)
            assertTrue("accent sur fond $seed", contrast(colors.primary, colors.backgroundTop) >= 4.5f)
        }
    }

    @Test
    fun `le texte reste lisible sur le fond et sur l'accent en theme clair`() {
        seeds.forEach { seed ->
            val colors = deriveArtworkColors(seed, light, dark = false)
            assertTrue("fond $seed", contrast(light.onSurface, colors.backgroundTop) >= 7f)
            assertTrue("bouton $seed", contrast(colors.onPrimary, colors.primary) >= 4.5f)
            assertTrue("pastille $seed", contrast(colors.onPrimaryContainer, colors.primaryContainer) >= 4.5f)
            assertTrue("accent sur fond $seed", contrast(colors.primary, colors.backgroundTop) >= 3f)
        }
    }
}
