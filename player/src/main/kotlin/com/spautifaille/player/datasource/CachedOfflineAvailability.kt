package com.spautifaille.player.datasource

import com.spautifaille.domain.di.IoDispatcher
import com.spautifaille.domain.model.AudioQuality
import com.spautifaille.domain.model.DownloadState
import com.spautifaille.domain.repository.DownloadRepository
import com.spautifaille.domain.repository.OfflineAvailability
import com.spautifaille.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [OfflineAvailability] : téléchargements terminés + titres du cache de streaming (voir [StreamCacheIndex]).
 *
 * Le `SimpleCache` n'expose pas de flux global de changements : tant qu'il y a un collecteur, l'index est relu
 * toutes les [pollMs] (lecture en mémoire de l'index, négligeable) ; les téléchargements, eux, sont réactifs.
 */
@Singleton
class CachedOfflineAvailability internal constructor(
    private val downloads: DownloadRepository,
    private val settings: SettingsRepository,
    private val scanCache: (quality: AudioQuality) -> Set<String>,
    private val io: CoroutineDispatcher,
    private val pollMs: Long,
) : OfflineAvailability {

    @Inject
    constructor(
        downloads: DownloadRepository,
        settings: SettingsRepository,
        mediaCache: MediaCache,
        @IoDispatcher io: CoroutineDispatcher,
    ) : this(
        downloads,
        settings,
        { quality -> StreamCacheIndex.playableVideoIds(mediaCache.cache, quality) },
        io,
        POLL_MS,
    )

    override fun observePlayableIds(): Flow<Set<String>> {
        val downloaded = downloads.observeDownloads()
            .map { list -> list.filter { it.state == DownloadState.COMPLETED }.mapTo(HashSet()) { it.track.id } }
            .distinctUntilChanged()
        val cached = settings.settings
            .map { it.audioQuality }
            .distinctUntilChanged()
            .flatMapLatest { quality ->
                flow {
                    while (true) {
                        emit(scanCache(quality))
                        delay(pollMs)
                    }
                }
            }
            .flowOn(io)
            .distinctUntilChanged()
        return combine(downloaded, cached) { a, b -> a + b }.distinctUntilChanged()
    }

    private companion object {
        const val POLL_MS = 4_000L
    }
}
