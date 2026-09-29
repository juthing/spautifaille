package com.spautifaille.player.datasource

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import com.spautifaille.domain.repository.DownloadRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chaîne de data sources du lecteur :
 *
 * ```
 * OfflineFirstDataSource ── fichier téléchargé ──────────────► FileDataSource
 *        └── sinon ► CacheDataSource(SimpleCache, clé = URI stable)
 *                        └► ResolvingDataSource(TrackUriResolver)
 *                              └► DefaultDataSource(file:// -> FileDataSource, http(s) -> YoutubeHttpDataSource)
 * ```
 * Le cache est AU-DESSUS de la résolution : sa clé est l'URI stable `spautifaille://track/<id>` (+ qualité) et non
 * l'URL de flux, qui change à chaque résolution.
 */
@OptIn(UnstableApi::class)
@Singleton
class PlayerDataSourceFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val streamResolver: StreamResolver,
    private val downloads: DownloadRepository,
    private val mediaCache: MediaCache,
) : DataSource.Factory {

    private val delegate: DataSource.Factory by lazy {
        val network = DefaultDataSource.Factory(context, YoutubeHttpDataSource.Factory(okHttpClient))
        val resolving = ResolvingDataSource.Factory(network, TrackUriResolver(streamResolver, downloads))
        val cached = CacheDataSource.Factory()
            .setCache(mediaCache.cache)
            .setUpstreamDataSourceFactory(resolving)
            .setCacheKeyFactory(CacheKeyFactory { spec ->
                TrackUri.videoId(spec.uri)?.let(streamResolver::cacheKey) ?: spec.key ?: spec.uri.toString()
            })
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        OfflineFirstDataSource.Factory(downloads::localFileBlocking, cached)
    }

    override fun createDataSource(): DataSource = delegate.createDataSource()
}
