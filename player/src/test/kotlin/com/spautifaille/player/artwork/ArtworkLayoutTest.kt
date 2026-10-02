package com.spautifaille.player.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ArtworkLayoutTest {

    private val black = 0xFF000000.toInt()

    /** Motif « photo » : jamais sombre ni uniforme. */
    private fun content(x: Int, y: Int): Int {
        val r = 90 + (x * 7 + y * 3) % 150
        val g = 80 + (x * 5 + y * 11) % 160
        val b = 100 + (x * 13 + y) % 140
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Image [width]x[height] dont seul le rectangle [inner] est « photo », le reste est noir (bandes). */
    private fun image(width: Int, height: Int, inner: PixelRect): IntArray =
        IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            if (x in inner.left until inner.right && y in inner.top until inner.bottom) content(x, y) else black
        }

    private fun bounds(width: Int, height: Int, inner: PixelRect) =
        ArtworkLayout.findContentBounds(image(width, height, inner), width, height)

    private fun assertNear(expected: PixelRect, actual: PixelRect, tolerance: Int = 4) {
        val message = "attendu $expected, obtenu $actual"
        assertTrue(message, abs(expected.left - actual.left) <= tolerance)
        assertTrue(message, abs(expected.top - actual.top) <= tolerance)
        assertTrue(message, abs(expected.right - actual.right) <= tolerance)
        assertTrue(message, abs(expected.bottom - actual.bottom) <= tolerance)
    }

    // --- Détection des bandes ---

    @Test
    fun `image sans bande - zone utile = image entiere`() {
        val full = PixelRect(0, 0, 320, 180)
        assertEquals(full, bounds(320, 180, full))
    }

    @Test
    fun `pochette carree entouree de bandes verticales - zone utile = le carre`() {
        val square = PixelRect(280, 0, 1000, 720) // 720x720 centré dans 1280x720
        assertNear(square, bounds(1280, 720, square))
    }

    @Test
    fun `miniature 4 3 avec bandes haut et bas - zone utile = le 16 9`() {
        val inner = PixelRect(0, 60, 640, 420) // sddefault 640x480 : contenu 640x360
        assertNear(inner, bounds(640, 480, inner))
    }

    @Test
    fun `pochette carree dans un 4 3 a bandes sur les quatre cotes`() {
        val square = PixelRect(140, 60, 500, 420)
        assertNear(square, bounds(640, 480, square))
    }

    @Test
    fun `bandes asymetriques ne sont pas retirees`() {
        val inner = PixelRect(0, 0, 640, 360) // bande en bas seulement
        assertEquals(PixelRect(0, 0, 640, 480), bounds(640, 480, inner))
    }

    @Test
    fun `pochette sombre avec un petit motif central n est pas recadree sur le motif`() {
        val inner = PixelRect(200, 180, 440, 300) // 240x120 : bandes bien plus épaisses que toute vignette réelle
        assertEquals(PixelRect(0, 0, 640, 480), bounds(640, 480, inner))
    }

    @Test
    fun `contenu a rapport inhabituel apres retrait des bandes est ignore`() {
        val inner = PixelRect(100, 0, 540, 480) // 440x480 = 0,92 : pas un rapport connu
        assertEquals(PixelRect(0, 0, 640, 480), bounds(640, 480, inner))
    }

    @Test
    fun `image entierement noire ou minuscule reste entiere`() {
        assertEquals(PixelRect(0, 0, 320, 180), ArtworkLayout.findContentBounds(IntArray(320 * 180) { black }, 320, 180))
        assertEquals(PixelRect(0, 0, 8, 8), ArtworkLayout.findContentBounds(IntArray(64), 8, 8))
    }

    // --- Recadrage ---

    @Test
    fun `image 16 9 est conservee en entier`() {
        assertEquals(PixelRect(0, 0, 1280, 720), ArtworkLayout.cropRegion(PixelRect(0, 0, 1280, 720)))
    }

    @Test
    fun `image plus large que 16 9 est recadree sur les cotes`() {
        val region = ArtworkLayout.cropRegion(PixelRect(0, 0, 1600, 640)) // 2,5
        assertEquals(1138, region.width) // 640 * 16/9
        assertEquals(640, region.height)
        assertEquals((1600 - 1138) / 2, region.left)
    }

    @Test
    fun `pochette carree est recadree en haut et en bas, centree`() {
        // 1200x1200 -> bande 1200x675 : 44 % de la hauteur est coupée, répartie à parts égales.
        assertEquals(PixelRect(0, 262, 1200, 937), ArtworkLayout.cropRegion(PixelRect(0, 0, 1200, 1200)))
    }

    @Test
    fun `4 3 est recadre en haut et en bas`() {
        val region = ArtworkLayout.cropRegion(PixelRect(0, 0, 640, 480))
        assertEquals(PixelRect(0, 60, 640, 420), region)
        assertEquals(16f / 9f, region.aspect, 0.01f)
    }

    @Test
    fun `le recadrage de tout rapport donne du 16 9 dans le rectangle`() {
        for ((w, h) in listOf(1200 to 1200, 640 to 480, 480 to 360, 3000 to 600, 300 to 900, 1280 to 720)) {
            val content = PixelRect(10, 20, 10 + w, 20 + h)
            val region = ArtworkLayout.cropRegion(content)
            assertEquals("${w}x$h", 16f / 9f, region.aspect, 0.02f)
            assertTrue(region.left >= content.left && region.right <= content.right)
            assertTrue(region.top >= content.top && region.bottom <= content.bottom)
        }
    }

    @Test
    fun `centerCrop respecte le rapport demande et reste dans le rectangle`() {
        assertEquals(PixelRect(260, 20, 760, 520), ArtworkLayout.centerCrop(PixelRect(10, 20, 1010, 520), 1f))
        assertEquals(PixelRect(0, 300, 400, 500), ArtworkLayout.centerCrop(PixelRect(0, 0, 400, 800), 2f))
    }
}
