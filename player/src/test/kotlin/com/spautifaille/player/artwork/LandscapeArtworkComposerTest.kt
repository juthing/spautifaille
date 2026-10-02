package com.spautifaille.player.artwork

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Dessin réel (Canvas natif) : taille de sortie, remplissage plein cadre, fond flou sous une pochette carrée. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LandscapeArtworkComposerTest {

    private fun solid(width: Int, height: Int, color: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    /** Image [width]x[height], noire sauf le rectangle [left, top, right, bottom) de la couleur [color]. */
    private fun withBars(width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int, color: Int): Bitmap {
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            if (x in left until right && y in top until bottom) color else Color.BLACK
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun Bitmap.channelDistance(x: Int, y: Int, color: Int): Int {
        val p = getPixel(x, y)
        return maxOf(
            kotlin.math.abs(Color.red(p) - Color.red(color)),
            kotlin.math.abs(Color.green(p) - Color.green(color)),
            kotlin.math.abs(Color.blue(p) - Color.blue(color)),
        )
    }

    @Test
    fun `la sortie fait toujours 1280x720 et la source n est pas modifiee`() {
        val source = solid(300, 300, Color.rgb(200, 40, 40))
        val out = LandscapeArtworkComposer.compose(source)
        assertEquals(1280, out.width)
        assertEquals(720, out.height)
        assertNotSame(source, out)
        assertEquals(300, source.width)
        assertEquals(Color.rgb(200, 40, 40), source.getPixel(10, 10))
    }

    @Test
    fun `image 16 9 remplit toute l affiche`() {
        val color = Color.rgb(30, 160, 220)
        val out = LandscapeArtworkComposer.compose(solid(640, 360, color))
        for ((x, y) in listOf(0 to 0, 1279 to 0, 0 to 719, 1279 to 719, 640 to 360)) {
            assertTrue("pixel ($x,$y) = ${Integer.toHexString(out.getPixel(x, y))}", out.channelDistance(x, y, color) <= 3)
        }
    }

    @Test
    fun `pochette carree - centre fidele, fond flou de la meme couleur et assombri`() {
        val art = Color.rgb(220, 60, 60)
        val out = LandscapeArtworkComposer.compose(solid(600, 600, art))
        // Centre : la pochette, intacte.
        assertTrue(out.channelDistance(640, 360, art) <= 3)
        // Les bords (hors de la pochette posée à 80 % de la hauteur) : teinte de la pochette, plus sombre, pas noire.
        val edge = out.getPixel(10, 360)
        assertTrue("fond rougeâtre", Color.red(edge) > Color.green(edge) + 40)
        assertTrue("assombri", Color.red(edge) < Color.red(art))
        assertTrue("pas noir", Color.red(edge) > 60)
        // L'alpha reste opaque partout.
        assertEquals(255, Color.alpha(out.getPixel(0, 0)))
    }

    @Test
    fun `miniature d une pochette carree entouree de bandes noires - les bandes disparaissent`() {
        val art = Color.rgb(40, 200, 90)
        // 1280x720, pochette 720x720 centrée, bandes noires de 280 px de chaque côté.
        val out = LandscapeArtworkComposer.compose(withBars(1280, 720, 280, 0, 1000, 720, art))
        assertTrue("centre = pochette", out.channelDistance(640, 360, art) <= 3)
        val edge = out.getPixel(10, 360)
        assertTrue("fond teinté de vert, pas noir", Color.green(edge) > 60 && Color.green(edge) > Color.red(edge) + 20)
    }
}
