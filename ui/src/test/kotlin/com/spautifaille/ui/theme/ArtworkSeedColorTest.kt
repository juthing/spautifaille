package com.spautifaille.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import androidx.palette.graphics.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Choix de la couleur source parmi les swatches Palette (Robolectric : Palette lit `android.graphics.Color`). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArtworkSeedColorTest {

    private fun swatch(color: Int, population: Int) = Palette.Swatch(color, population)

    /** `Palette.from(swatches)` n'enregistre aucune cible (vibrant, muted...) : on les ajoute comme le fait l'extraction d'un bitmap. */
    private fun palette(vararg swatches: Palette.Swatch): Palette =
        Palette.Builder(swatches.toList())
            .addTarget(Target.LIGHT_VIBRANT)
            .addTarget(Target.VIBRANT)
            .addTarget(Target.DARK_VIBRANT)
            .addTarget(Target.LIGHT_MUTED)
            .addTarget(Target.MUTED)
            .addTarget(Target.DARK_MUTED)
            .generate()

    @Test
    fun `la couleur source prefere un swatch vibrant`() {
        val vibrant = 0xFFE53935.toInt()
        val palette = palette(swatch(0xFF303030.toInt(), 500), swatch(vibrant, 100))
        val seed = pickSeed(palette)
        assertNotNull(seed)
        assertEquals(Color(vibrant), seed)
    }

    @Test
    fun `la couleur source retombe sur le dominant pour une pochette presque grise`() {
        val gray = 0xFF777777.toInt()
        val palette = palette(swatch(gray, 900), swatch(0xFFE53935.toInt(), 10))
        assertEquals(Color(gray), pickSeed(palette))
    }
}
