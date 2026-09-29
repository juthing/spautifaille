package com.spautifaille.player.datasource

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo

/**
 * Politique de rechargement au niveau du `Loader` (avant que l'erreur n'arrive au lecteur) :
 * - 403 / 410 : l'URL de flux a expiré ; on l'invalide et on retente aussitôt (une fois) avec une URL fraîche ;
 * - échec de résolution ([StreamResolutionException]) ou autre code HTTP d'échec définitif : pas de nouvelle
 *   tentative ici, l'erreur remonte au lecteur où `PlaybackErrorHandler` décide (attente réseau, saut...) ;
 * - le reste (coupure réseau...) : comportement par défaut d'ExoPlayer.
 */
@OptIn(UnstableApi::class)
class StreamLoadErrorHandlingPolicy(private val streamResolver: StreamResolver) : DefaultLoadErrorHandlingPolicy() {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorInfo): Long {
        val chain = generateSequence<Throwable>(loadErrorInfo.exception) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
        if (chain.any { it is StreamResolutionException }) return C.TIME_UNSET

        val http = chain.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        if (http != null) {
            return when (http.responseCode) {
                403, 410 -> {
                    if (loadErrorInfo.errorCount > 1) return C.TIME_UNSET
                    TrackUri.videoId(loadErrorInfo.loadEventInfo.dataSpec.uri)?.let(streamResolver::invalidate)
                    0L
                }
                404, 416 -> C.TIME_UNSET
                else -> super.getRetryDelayMsFor(loadErrorInfo)
            }
        }
        return super.getRetryDelayMsFor(loadErrorInfo)
    }

    private companion object {
        const val MAX_CAUSE_DEPTH = 8
    }
}
