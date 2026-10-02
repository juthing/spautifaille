package com.spautifaille.player.artwork

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF

/**
 * Fabrique l'affiche paysage 1280x720 du lecteur système (carte média, écran de verrouillage) à partir d'une image
 * quelconque (miniature YouTube, pochette carrée...). La géométrie vit dans [ArtworkLayout] ; ici, uniquement le
 * dessin :
 * - image déjà ~16:9 (une fois ses bandes noires retirées) : recadrée au centre, plein cadre ;
 * - sinon (pochette carrée, 4:3...) : posée au centre d'une version agrandie, floue et assombrie d'elle-même.
 */
internal object LandscapeArtworkComposer {

    private const val BLUR_WIDTH = 64
    private const val BLUR_RADIUS = 3
    private const val BLUR_PASSES = 3

    /** Voile noir (0..255) sur le fond flou : la pochette ressort et le texte du lecteur reste lisible. */
    private const val BACKGROUND_DIM_ALPHA = 80
    private const val CORNER_RADIUS_RATIO = 0.04f

    /** Retourne toujours un nouveau bitmap [ArtworkLayout.CANVAS_WIDTH] x [ArtworkLayout.CANVAS_HEIGHT] ; [source] n'est pas modifié. */
    fun compose(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val content = ArtworkLayout.findContentBounds(pixels, width, height)

        val output = Bitmap.createBitmap(ArtworkLayout.CANVAS_WIDTH, ArtworkLayout.CANVAS_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        when (val plan = ArtworkLayout.plan(content)) {
            is ArtworkPlan.Fill -> drawScaled(canvas, source, plan.source, Rect(0, 0, output.width, output.height))
            is ArtworkPlan.Fit -> {
                drawBlurredBackground(canvas, source, plan.backgroundSource, output.width, output.height)
                val destination = plan.destination
                val radius = destination.height * CORNER_RADIUS_RATIO
                canvas.save()
                canvas.clipPath(
                    Path().apply {
                        addRoundRect(
                            RectF(destination.left.toFloat(), destination.top.toFloat(), destination.right.toFloat(), destination.bottom.toFloat()),
                            radius,
                            radius,
                            Path.Direction.CW,
                        )
                    },
                )
                drawScaled(canvas, source, plan.content, Rect(destination.left, destination.top, destination.right, destination.bottom))
                canvas.restore()
            }
        }
        return output
    }

    private fun drawBlurredBackground(canvas: Canvas, source: Bitmap, region: PixelRect, width: Int, height: Int) {
        val blurHeight = (BLUR_WIDTH * height / width).coerceAtLeast(1)
        val small = scaleDown(source, region, BLUR_WIDTH, blurHeight)
        val pixels = IntArray(small.width * small.height)
        small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        ArtworkLayout.boxBlur(pixels, small.width, small.height, BLUR_RADIUS, BLUR_PASSES)
        val blurred = Bitmap.createBitmap(pixels, small.width, small.height, Bitmap.Config.ARGB_8888)
        canvas.drawBitmap(blurred, null, Rect(0, 0, width, height), newPaint())
        canvas.drawColor(Color.argb(BACKGROUND_DIM_ALPHA, 0, 0, 0))
        blurred.recycle()
        if (small !== source) small.recycle()
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
