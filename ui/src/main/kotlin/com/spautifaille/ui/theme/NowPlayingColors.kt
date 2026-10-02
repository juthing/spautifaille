package com.spautifaille.ui.theme

import android.content.Context
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Côté (px) auquel la pochette est décodée : largement suffisant pour trouver la couleur source. */
private const val ARTWORK_SIZE_PX = 128
private const val SEED_CACHE_SIZE = 64

/** Cache mémoire url -> couleur source (ARGB). Les échecs (réseau, pochette illisible) ne sont pas mémorisés. */
private val seedCache = LruCache<String, Int>(SEED_CACHE_SIZE)

/**
 * Schémas générés depuis la pochette [artworkUrl] (thème « Musique en cours »). Le chargement (Coil, bitmap
 * logiciel de 128 px), la quantification et la génération des schémas se font hors du thread principal ; la
 * couleur source est mise en cache par URL. La valeur précédente est conservée pendant le calcul pour que la
 * transition entre deux titres reste fluide ; `null` sans URL (rien ne joue) ou si la pochette est illisible,
 * auquel cas le thème retombe sur [resolvePalette]. Toujours `null` si [enabled] est faux (rien n'est chargé).
 */
@Composable
internal fun rememberNowPlayingSchemes(artworkUrl: String?, enabled: Boolean): State<SeedSchemes?> {
    if (LocalInspectionMode.current) return remember { mutableStateOf(null) }
    val context = LocalContext.current.applicationContext
    val url = artworkUrl?.takeIf { enabled && it.isNotBlank() }
    return produceState<SeedSchemes?>(initialValue = null, key1 = url) {
        if (url == null) {
            value = null
            return@produceState
        }
        val seed = withContext(Dispatchers.Default) { cachedSeed(context, url) }
        value = seed?.let { withContext(Dispatchers.Default) { seedSchemes(it) } }
    }
}

private suspend fun cachedSeed(context: Context, url: String): Int? {
    seedCache[url]?.let { return it }
    return loadSeed(context, url)?.also { seedCache.put(url, it) }
}

private suspend fun loadSeed(context: Context, url: String): Int? = try {
    val request = ImageRequest.Builder(context)
        .data(url)
        .size(Size(ARTWORK_SIZE_PX, ARTWORK_SIZE_PX))
        .allowHardware(false) // On lit les pixels : pas de bitmap matériel.
        .build()
    val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
    bitmap?.let {
        val pixels = IntArray(it.width * it.height)
        it.getPixels(pixels, 0, it.width, 0, 0, it.width, it.height)
        seedColorFromPixels(pixels)
    }
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}
