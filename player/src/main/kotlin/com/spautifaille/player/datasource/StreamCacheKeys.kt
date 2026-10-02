package com.spautifaille.player.datasource

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import com.spautifaille.domain.model.AudioQuality

/**
 * Clés du cache de streaming : `<videoId>#<QUALITÉ>`, stables (jamais l'URL de flux, qui expire). La qualité fait
 * partie de la clé pour ne jamais mélanger deux encodages dans les mêmes octets.
 */
object StreamCacheKeys {
    private const val SEPARATOR = '#'

    fun of(videoId: String, quality: AudioQuality): String = "$videoId$SEPARATOR${quality.name}"

    /** Identifiant vidéo d'une clé de la qualité [quality], ou null (autre qualité / clé étrangère). */
    fun videoIdOrNull(key: String, quality: AudioQuality): String? =
        key.removeSuffix("$SEPARATOR${quality.name}").takeIf { it.length < key.length && it.isNotEmpty() }
}

/**
 * Lecture de l'index du `SimpleCache` : quels titres peuvent démarrer sans réseau ?
 *
 * Le cache est placé AU-DESSUS de la résolution (voir `PlayerDataSourceFactory`) : un titre présent dans le cache est
 * lu sans jamais résoudre d'URL de flux. Un titre est lisible hors ligne si ses octets sont présents dès le début :
 * - entièrement (longueur du contenu connue et couverte) ; ou
 * - en partie, si au moins [MIN_PARTIAL_BYTES] consécutifs depuis le début sont en cache (la lecture démarre et
 *   s'arrête, ou attend le réseau, au premier octet manquant).
 */
@OptIn(UnstableApi::class)
object StreamCacheIndex {
    /** ~15 s d'audio Opus/AAC standard : en dessous, démarrer n'a pas de sens. */
    const val MIN_PARTIAL_BYTES = 256L * 1024L

    fun playableVideoIds(cache: Cache, quality: AudioQuality): Set<String> {
        val ids = HashSet<String>()
        for (key in cache.keys) {
            val videoId = StreamCacheKeys.videoIdOrNull(key, quality) ?: continue
            if (isPlayable(cache, key)) ids += videoId
        }
        return ids
    }

    fun isPlayable(cache: Cache, key: String): Boolean {
        val contentLength = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        if (contentLength != C.LENGTH_UNSET.toLong() && contentLength > 0 && cache.isCached(key, 0, contentLength)) {
            return true
        }
        // > 0 : octets consécutifs en cache depuis 0 ; <= 0 : trou dès le début.
        val prefix = cache.getCachedLength(key, 0, Long.MAX_VALUE)
        return prefix >= MIN_PARTIAL_BYTES
    }
}
