package com.spautifaille.ui.theme

import android.content.Context
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Extraction de la couleur source d'une pochette : source de vérité unique, partagée par le lecteur plein écran
 * (`FullPlayerScreen`) et par le thème « Musique en cours » de l'application (`SpautifailleAppUi`). Même image
 * (URL du titre), même algorithme (Palette), même cache : les deux voient toujours la même couleur.
 */

/** Côté (px) auquel la pochette est décodée pour l'extraction : largement suffisant pour Palette. */
private const val PALETTE_BITMAP_SIZE = 128
private const val PALETTE_MAX_COLORS = 24
private const val SEED_CACHE_SIZE = 64

/** Cache mémoire url -> couleur (ARGB) : réouvrir le lecteur ou revenir sur un titre ne recalcule rien. Les échecs ne sont pas mémorisés. */
private val seedCache = LruCache<String, Int>(SEED_CACHE_SIZE)

/**
 * Couleur dominante (vibrante si possible) de la pochette [url], extraite hors du thread principal via un
 * bitmap Coil (logiciel) et Palette. La valeur précédente est conservée pendant le calcul pour éviter un
 * retour brutal aux couleurs du thème entre deux titres ; `null` si pas de pochette ([url] nulle ou vide) ou en cas
 * d'échec. Si la couleur est déjà en cache (l'autre consommateur l'a calculée), elle est disponible dès la première
 * composition.
 */
@Composable
internal fun rememberArtworkSeedColor(url: String?): State<Color?> {
    if (LocalInspectionMode.current) return remember { mutableStateOf(null) }
    val context = LocalContext.current.applicationContext
    return produceState<Color?>(initialValue = url?.let { seedCache[it] }?.let(::Color), key1 = url) {
        if (url.isNullOrBlank()) {
            value = null
            return@produceState
        }
        val cached = seedCache[url]
        value = if (cached != null) {
            Color(cached)
        } else {
            val seed = withContext(Dispatchers.Default) { extractSeedColor(context, url) }
            if (seed != null) seedCache.put(url, seed.toArgb())
            seed
        }
    }
}

private suspend fun extractSeedColor(context: Context, url: String): Color? = try {
    val request = ImageRequest.Builder(context)
        .data(url)
        .size(Size(PALETTE_BITMAP_SIZE, PALETTE_BITMAP_SIZE))
        .allowHardware(false) // Palette lit les pixels : pas de bitmap matériel.
        .build()
    val result = SingletonImageLoader.get(context).execute(request)
    val bitmap = (result as? SuccessResult)?.image?.toBitmap()
    bitmap?.let { pickSeed(Palette.from(it).maximumColorCount(PALETTE_MAX_COLORS).generate()) }
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/** Préfère un swatch vibrant ; retombe sur le dominant si la pochette est presque grise (accent marginal). */
internal fun pickSeed(palette: Palette): Color? {
    val dominant = palette.dominantSwatch
    val vibrant = palette.vibrantSwatch
        ?: palette.darkVibrantSwatch
        ?: palette.lightVibrantSwatch
        ?: palette.mutedSwatch
    val chosen = when {
        vibrant == null -> dominant
        dominant != null && vibrant.population * MARGINAL_RATIO < dominant.population && dominant.hsl[1] < 0.15f -> dominant
        else -> vibrant
    }
    return chosen?.let { Color(it.rgb) }
}

private const val MARGINAL_RATIO = 8
