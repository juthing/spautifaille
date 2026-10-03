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

/** Dessin réel (Canvas natif) : taille de sortie, remplissage plein cadre (recadrage central) sans bande ni fond flou. */
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
    fun `pochette carree - recadree plein cadre, sans bande ni fond flou`() {
        val art = Color.rgb(220, 60, 60)
        val out = LandscapeArtworkComposer.compose(solid(600, 600, art))
        // Partout, y compris aux bords et aux coins, la couleur de la pochette (pas de voile sombre).
        for ((x, y) in listOf(0 to 0, 1279 to 0, 0 to 719, 1279 to 719, 10 to 360, 640 to 5, 640 to 360)) {
            assertTrue("pixel ($x,$y) = ${Integer.toHexString(out.getPixel(x, y))}", out.channelDistance(x, y, art) <= 3)
        }
        assertEquals(255, Color.alpha(out.getPixel(0, 0)))
    }

    @Test
    fun `pochette carree - le recadrage garde la bande centrale`() {
        // Haut et bas de la pochette (22 % chacun) sont coupés ; la bande centrale est conservée.
        val top = Color.rgb(250, 0, 0)
        val middle = Color.rgb(0, 250, 0)
        val bottom = Color.rgb(0, 0, 250)
        val pixels = IntArray(400 * 400) { i ->
            when (i / 400) {
                in 0 until 80 -> top
                in 320 until 400 -> bottom
                else -> middle
            }
        }
        val out = LandscapeArtworkComposer.compose(Bitmap.createBitmap(pixels, 400, 400, Bitmap.Config.ARGB_8888))
        // Bande 400x225 centrée : y de 87 à 312, donc ni rouge ni bleu.
        for (y in listOf(0, 5, 360, 714, 719)) {
            assertTrue("y=$y", out.channelDistance(640, y, middle) <= 3)
        }
    }

    @Test
    fun `miniature 4 3 a bandes noires - bandes retirees, plein cadre`() {
        val art = Color.rgb(40, 200, 90)
        // sddefault : 640x480, contenu 640x360 entre deux bandes noires de 60 px.
        val out = LandscapeArtworkComposer.compose(withBars(640, 480, 0, 60, 640, 420, art))
        for ((x, y) in listOf(0 to 0, 1279 to 0, 0 to 719, 1279 to 719, 640 to 360)) {
            assertTrue("pixel ($x,$y) = ${Integer.toHexString(out.getPixel(x, y))}", out.channelDistance(x, y, art) <= 3)
        }
    }

    @Test
    fun `miniature d une pochette carree entouree de bandes noires - bandes retirees, pochette recadree plein cadre`() {
        val art = Color.rgb(40, 200, 90)
        // 1280x720, pochette 720x720 centrée, bandes noires de 280 px de chaque côté.
        val out = LandscapeArtworkComposer.compose(withBars(1280, 720, 280, 0, 1000, 720, art))
        for ((x, y) in listOf(0 to 0, 1279 to 0, 0 to 719, 1279 to 719, 10 to 360, 640 to 360)) {
            assertTrue("pixel ($x,$y) = ${Integer.toHexString(out.getPixel(x, y))}", out.channelDistance(x, y, art) <= 3)
        }
    }
}
