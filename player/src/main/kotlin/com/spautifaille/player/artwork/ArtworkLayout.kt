package com.spautifaille.player.artwork

import kotlin.math.abs
import kotlin.math.roundToInt

/** Rectangle de pixels, bornes droite et basse exclues. */
internal data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val aspect: Float get() = width.toFloat() / height
}

/**
 * Géométrie pure (sans API Android, donc testable en JVM) de l'affiche paysage du lecteur système :
 * détection des bandes noires d'une miniature (letterbox / pillarbox) et recadrage central en 16:9.
 */
internal object ArtworkLayout {
    const val CANVAS_WIDTH = 1_280
    const val CANVAS_HEIGHT = 720
    const val TARGET_ASPECT = CANVAS_WIDTH.toFloat() / CANVAS_HEIGHT

    // --- Détection des bandes ---

    /** Luminance (0..255) en dessous de laquelle un pixel compte comme « noir ». */
    private const val DARK_LUMA = 28

    /** Part minimale de pixels sombres pour qu'une ligne / colonne soit une bande. */
    private const val BAR_DARK_RATIO = 0.97f

    /** Une bande doit faire au moins cette part de la dimension, et les deux côtés différer d'au plus [BAR_SYMMETRY]. */
    private const val MIN_BAR_FRACTION = 0.02f
    private const val BAR_SYMMETRY = 0.04f

    /** Marge retirée du côté des bandes détectées (franges de compression JPEG). */
    private const val BAR_INSET_PX = 2

    /** Rapports d'image vraisemblables après retrait des bandes (carré, 4:3, 3:2, 16:9, cinéma). */
    private val KNOWN_ASPECTS = floatArrayOf(1f, 4f / 3f, 1.5f, 16f / 9f, 2f, 2.35f, 2.39f)
    private const val ASPECT_TOLERANCE = 0.04f

    /**
     * Zone utile de l'image [pixels] (ARGB, [width] x [height]) une fois retirées les bandes noires symétriques
     * (haut/bas et gauche/droite). Retourne l'image entière si rien de net n'est détecté : une pochette
     * sombre avec un motif au centre ne doit pas être « recadrée » sur son motif, d'où les garde-fous (symétrie,
     * taille minimale, rapport d'aspect plausible du contenu restant).
     */
    fun findContentBounds(pixels: IntArray, width: Int, height: Int): PixelRect {
        val full = PixelRect(0, 0, width, height)
        if (width < MIN_ANALYSIS_SIZE || height < MIN_ANALYSIS_SIZE || pixels.size < width * height) return full

        val maxTrimY = (height * MAX_TRIM_FRACTION).toInt()
        val maxTrimX = (width * MAX_TRIM_FRACTION).toInt()
        var top = 0
        while (top < maxTrimY && isDarkRow(pixels, width, top, 0, width)) top++
        var bottom = 0
        while (bottom < maxTrimY && isDarkRow(pixels, width, height - 1 - bottom, 0, width)) bottom++
        val rowsFrom = top
        val rowsTo = height - bottom
        var left = 0
        while (left < maxTrimX && isDarkColumn(pixels, width, left, rowsFrom, rowsTo)) left++
        var right = 0
        while (right < maxTrimX && isDarkColumn(pixels, width, width - 1 - right, rowsFrom, rowsTo)) right++

        if (top >= maxTrimY || bottom >= maxTrimY || left >= maxTrimX || right >= maxTrimX) return full
        if (!validBars(top, bottom, height) || !validBars(left, right, width)) return full
        if (top == 0 && bottom == 0 && left == 0 && right == 0) return full

        val bounds = PixelRect(
            left = if (left > 0) left + BAR_INSET_PX else 0,
            top = if (top > 0) top + BAR_INSET_PX else 0,
            right = if (right > 0) width - right - BAR_INSET_PX else width,
            bottom = if (bottom > 0) height - bottom - BAR_INSET_PX else height,
        )
        if (bounds.width < MIN_ANALYSIS_SIZE || bounds.height < MIN_ANALYSIS_SIZE) return full
        val aspect = bounds.aspect
        return if (KNOWN_ASPECTS.any { abs(aspect / it - 1f) <= ASPECT_TOLERANCE }) bounds else full
    }

    private const val MIN_ANALYSIS_SIZE = 16
    /** Plus épaisse bande plausible (carré dans du 16:9 : 22 % de chaque côté) ; au-delà, c'est une image sombre, pas des bandes. */
    private const val MAX_TRIM_FRACTION = 0.30f

    /** Bandes valides sur un axe : aucune (0/0), ou deux côtés assez épais et à peu près égaux. */
    private fun validBars(before: Int, after: Int, size: Int): Boolean {
        if (before == 0 && after == 0) return true
        if (before == 0 || after == 0) return false
        val min = (size * MIN_BAR_FRACTION).toInt().coerceAtLeast(1)
        return before >= min && after >= min && abs(before - after) <= size * BAR_SYMMETRY + BAR_INSET_PX
    }

    private fun isDark(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1_000 <= DARK_LUMA
    }

    private fun isDarkRow(pixels: IntArray, width: Int, y: Int, fromX: Int, toX: Int): Boolean {
        var dark = 0
        val base = y * width
        for (x in fromX until toX) if (isDark(pixels[base + x])) dark++
        return dark >= (toX - fromX) * BAR_DARK_RATIO
    }

    private fun isDarkColumn(pixels: IntArray, width: Int, x: Int, fromY: Int, toY: Int): Boolean {
        var dark = 0
        for (y in fromY until toY) if (isDark(pixels[y * width + x])) dark++
        return dark >= (toY - fromY) * BAR_DARK_RATIO
    }

    // --- Recadrage ---

    /**
     * Zone de [content] (zone utile, bandes noires déjà retirées) à afficher : le plus grand rectangle 16:9 centré,
     * pour que l'image remplisse toute l'affiche sans bande ni fond. Une pochette carrée perd donc ~44 % de sa hauteur
     * (moitié en haut, moitié en bas).
     */
    fun cropRegion(content: PixelRect): PixelRect = centerCrop(content, TARGET_ASPECT)

    /** Plus grand rectangle centré de [rect] d'aspect [aspect]. */
    fun centerCrop(rect: PixelRect, aspect: Float): PixelRect {
        val current = rect.aspect
        return if (current > aspect) {
            val w = (rect.height * aspect).roundToInt().coerceIn(1, rect.width)
            val left = rect.left + (rect.width - w) / 2
            PixelRect(left, rect.top, left + w, rect.bottom)
        } else {
            val h = (rect.width / aspect).roundToInt().coerceIn(1, rect.height)
            val top = rect.top + (rect.height - h) / 2
            PixelRect(rect.left, top, rect.right, top + h)
        }
    }
}
