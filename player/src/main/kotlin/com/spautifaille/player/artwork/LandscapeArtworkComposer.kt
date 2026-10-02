package com.spautifaille.player.artwork

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

/**
 * Fabrique l'affiche paysage 1280x720 du lecteur système (carte média, écran de verrouillage) à partir d'une image
 * quelconque (miniature YouTube, pochette carrée...). La géométrie vit dans [ArtworkLayout] ; ici, uniquement le
 * dessin : les bandes noires intégrées à la miniature sont retirées, puis le contenu utile est **recadré au centre en
 * 16:9 et remplit tout le cadre** (jamais de bande ni de fond, quel que soit le rapport de l'image).
 */
internal object LandscapeArtworkComposer {

    /** Retourne toujours un nouveau bitmap [ArtworkLayout.CANVAS_WIDTH] x [ArtworkLayout.CANVAS_HEIGHT] ; [source] n'est pas modifié. */
    fun compose(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val region = ArtworkLayout.cropRegion(ArtworkLayout.findContentBounds(pixels, width, height))

        val output = Bitmap.createBitmap(ArtworkLayout.CANVAS_WIDTH, ArtworkLayout.CANVAS_HEIGHT, Bitmap.Config.ARGB_8888)
        drawScaled(Canvas(output), source, region, Rect(0, 0, output.width, output.height))
        return output
    }

    /** Dessine [region] de [source] dans [destination], en réduisant par moitiés pour éviter le crénelage. */
    private fun drawScaled(canvas: Canvas, source: Bitmap, region: PixelRect, destination: Rect) {
        val scaled = scaleDown(source, region, destination.width(), destination.height())
        canvas.drawBitmap(scaled, null, destination, newPaint())
        if (scaled !== source) scaled.recycle()
    }

    /**
     * Recadre [source] sur [region] puis la réduit par paliers d'un facteur 2 maximum tant qu'elle reste au moins
     * deux fois plus grande que la cible (le filtre bilinéaire seul crénèle au-delà de x2). Peut retourner [source].
     */
    private fun scaleDown(source: Bitmap, region: PixelRect, targetWidth: Int, targetHeight: Int): Bitmap {
        val whole = region.left == 0 && region.top == 0 && region.width == source.width && region.height == source.height
        var current = if (whole) source else Bitmap.createBitmap(source, region.left, region.top, region.width, region.height)
        while (current.width >= targetWidth * 2 && current.height >= targetHeight * 2) {
            val next = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
            if (current !== source) current.recycle()
            current = next
        }
        return current
    }

    /** Un [Paint] par dessin : la composition peut tourner sur plusieurs threads. */
    private fun newPaint() = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
}
