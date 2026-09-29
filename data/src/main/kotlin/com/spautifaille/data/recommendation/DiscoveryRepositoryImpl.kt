package com.spautifaille.data.recommendation

import android.util.Log
import com.spautifaille.data.local.DiscoveryDao
import com.spautifaille.data.local.DiscoveryTrackEntity
import com.spautifaille.data.local.toDomain
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.recommendation.Candidate
import com.spautifaille.domain.recommendation.Discovery
import com.spautifaille.domain.recommendation.DiscoveryEngine
import com.spautifaille.domain.recommendation.DiscoveryInput
import com.spautifaille.domain.recommendation.DiscoveryRepository
import com.spautifaille.domain.recommendation.RecommendationSource
import com.spautifaille.domain.recommendation.SeedSelector
import com.spautifaille.domain.repository.LibraryRepository
import com.spautifaille.domain.repository.PlaylistRepository
import com.spautifaille.domain.repository.TrackCache
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Découverte : titres de départ ([SeedSelector]) → similarité auprès des [RecommendationSource] (en parallèle,
 * [PARALLELISM] requêtes à la fois, échecs tolérés titre par titre) → [DiscoveryEngine] → cache Room
 * (`discovery_tracks`, dans l'ordre d'affichage). Les résultats restent lisibles hors ligne.
 */
@Singleton
class DiscoveryRepositoryImpl internal constructor(
    private val discoveryDao: DiscoveryDao,
    private val library: LibraryRepository,
    playlists: PlaylistRepository,
    private val trackCache: TrackCache,
    private val sources: Set<RecommendationSource>,
    private val scheduler: () -> Unit,
    private val clock: () -> Long,
    private val randomFactory: () -> Random,
    private val engine: DiscoveryEngine = DiscoveryEngine(),
) : DiscoveryRepository {

    @Inject
    constructor(
        discoveryDao: DiscoveryDao,
        library: LibraryRepository,
        playlists: PlaylistRepository,
        trackCache: TrackCache,
        sources: Set<@JvmSuppressWildcards RecommendationSource>,
        scheduler: DiscoveryRefreshScheduler,
    ) : this(
        discoveryDao, library, playlists, trackCache, sources, scheduler::schedule,
        System::currentTimeMillis, { Random.Default },
    )

    private val seedSelector = SeedSelector(library, playlists, clock = clock)
    private val mutex = Mutex()

    override fun observe(): Flow<Discovery?> =
        discoveryDao.observeWithTracks()
            .map { rows ->
                if (rows.isEmpty()) {
                    null
                } else {
                    Discovery(
                        tracks = rows.map { it.track.toDomain() },
                        generatedAt = rows.maxOf { it.item.generatedAt },
                    )
                }
            }
            .distinctUntilChanged()

    override fun scheduleRefresh() {
        try {
            scheduler()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to schedule discovery refresh", e)
        }
    }

    override suspend fun refresh() {
        // Un calcul est déjà en cours : on attend son résultat au lieu d'en relancer un.
        if (mutex.isLocked) {
            mutex.withLock { }
            return
        }
        mutex.withLock { doRefresh() }
    }

    private suspend fun doRefresh() {
        val now = clock()
        val random = randomFactory()
        val seeds = seedSelector.select(random)
        if (seeds.isEmpty()) {
            discoveryDao.replace(emptyList())
            return
        }

        val results = fetchSimilar(seeds)
        val succeeded = results.filterIsInstance<SeedResult.Ok>()
        val failures = results.filterIsInstance<SeedResult.Failed>()
        if (succeeded.isEmpty()) {
            val first = failures.first().error
            throw first as? AppException ?: AppException(AppError.Unknown(first.message), first)
        }

        val items = engine.build(
            DiscoveryInput(
                seeds = seeds,
                similarBySeed = succeeded.associate { it.seed.id to it.candidates },
                recentlyPlayedIds = library.recentlyPlayedIds(now - RECENT_WINDOW_MS),
                libraryIds = library.libraryTrackIds(),
                likedIds = library.observeLikedIds().first(),
            ),
            random,
        )

        // Les lignes `tracks` doivent exister avant `discovery_tracks` (clé étrangère).
        trackCache.put(items.map { it.track })
        discoveryDao.replace(
            items.mapIndexed { index, item ->
                DiscoveryTrackEntity(
                    position = index,
                    trackId = item.track.id,
                    seedTrackId = item.seedId,
                    source = item.sourceId,
                    generatedAt = now,
                )
            },
        )
    }

    private sealed interface SeedResult {
        data class Ok(val seed: Track, val candidates: List<Candidate>) : SeedResult
        data class Failed(val seed: Track, val error: Throwable) : SeedResult
    }

    private suspend fun fetchSimilar(seeds: List<Track>): List<SeedResult> = coroutineScope {
        val permits = Semaphore(PARALLELISM)
        seeds.map { seed -> async { permits.withPermit { fetchForSeed(seed) } } }.awaitAll()
    }

    /** Interroge toutes les sources pour [seed] ; n'échoue que si aucune source n'a répondu. */
    private suspend fun fetchForSeed(seed: Track): SeedResult {
        if (sources.isEmpty()) return SeedResult.Ok(seed, emptyList())
        val candidates = ArrayList<Candidate>()
        var firstError: Throwable? = null
        var answered = false
        for (source in sources.sortedBy { it.id }) {
            try {
                source.similar(seed, PER_SEED_LIMIT).forEach { candidates += Candidate(it, source.id) }
                answered = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Source ${source.id} failed for seed ${seed.id}", e)
                if (firstError == null) firstError = e
            }
        }
        return if (answered) SeedResult.Ok(seed, candidates) else SeedResult.Failed(seed, firstError!!)
    }

    companion object {
        private const val TAG = "DiscoveryRepository"

        /** Requêtes réseau simultanées. */
        const val PARALLELISM = 3
        const val PER_SEED_LIMIT = 25

        /** Les titres écoutés dans cette fenêtre (14 jours) ne sont pas proposés. */
        const val RECENT_WINDOW_MS = 14L * 24 * 60 * 60 * 1000
    }
}
