package com.spautifaille.player.datasource

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.repository.DownloadRepository
import java.io.File

/**
 * Transforme `spautifaille://track/<videoId>` en source réellement lisible, au moment de l'ouverture :
 * 1. fichier téléchargé (priorité, lecture hors ligne) -> `file://` ;
 * 2. sinon URL de flux résolue par [StreamResolver] + en-têtes requis (User-Agent...).
 *
 * `ResolvingDataSource` s'exécute sur le thread de chargement, qui a le droit de bloquer.
 * La clé de cache de la [DataSpec] reste celle de l'URI stable.
 */
@OptIn(UnstableApi::class)
class TrackUriResolver(
    private val streamResolver: StreamResolver,
    private val downloads: DownloadRepository,
    private val fileExists: (String) -> Boolean = { File(it).isFile },
) : ResolvingDataSource.Resolver {

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val videoId = TrackUri.videoId(dataSpec.uri) ?: return dataSpec
        val stableKey = dataSpec.key ?: dataSpec.uri.toString()

        downloads.localFileBlocking(videoId)?.takeIf(fileExists)?.let { path ->
            return dataSpec.buildUpon()
                .setUri(Uri.fromFile(File(path)))
                .setKey(stableKey)
                .build()
        }

        val stream = streamResolver.resolveBlocking(videoId)
        if (stream.dashManifest != null) {
            // Les manifestes DASH ne peuvent pas être servis par un ResolvingDataSource (la MediaSource est choisie
            // avant la résolution). Le client VisionOS renvoie des flux progressifs : on saute ce cas rare.
            throw StreamResolutionException(AppError.NoAudioStream)
        }
        return dataSpec.buildUpon()
            .setUri(Uri.parse(stream.url))
            .setKey(stableKey)
            .setHttpRequestHeaders(dataSpec.httpRequestHeaders + stream.headers)
            .build()
    }
}
