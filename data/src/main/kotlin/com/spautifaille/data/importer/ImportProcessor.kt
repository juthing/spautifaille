package com.spautifaille.data.importer

import android.util.Log
import com.spautifaille.data.local.ImportDao
import com.spautifaille.data.local.ImportItemEntity
import com.spautifaille.data.local.ImportJobEntity
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.importer.ImportedTrack
import com.spautifaille.domain.importer.MatchCandidate
import com.spautifaille.domain.importer.MatchResult
import com.spautifaille.domain.importer.MatchStatus
import com.spautifaille.domain.matching.TrackMatcher
import com.spautifaille.domain.model.SearchFilter
import com.spautifaille.domain.model.SearchResult
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.repository.StreamRepository
import com.spautifaille.domain.repository.TrackCache
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Fin d'un passage de [ImportProcessor.process]. */
sealed interface ImportOutcome {
    /** Tous les titres ont été traités (ou le job n'existe plus). */
    data object Done : ImportOutcome

    /** Interrompu par une erreur récupérable : à relancer plus tard (les titres déjà traités sont conservés). */
    data class Retry(val reason: AppError) : ImportOutcome
}

/**
 * Boucle de traitement d'un job d'import, indépendante de WorkManager (donc testable) : pour chaque titre
 * `PENDING`, dans l'ordre de la source, résout le titre YouTube (id connu ou recherche + [TrackMatcher]),
 * l'ajoute à la playlist cible et met à jour les compteurs, le tout dans une transaction par titre. Comme seuls les
 * titres `PENDING` sont traités, un nouvel appel reprend là où le précédent s'est arrêté.
 */
class ImportProcessor internal constructor(
    private val store: ImportStore,
    private val importDao: ImportDao,
    private val streamRepository: StreamRepository,
    private val trackCache: TrackCache,
    private val matcher: TrackMatcher,
    /** Pause après chaque requête réseau, pour ne pas déclencher la protection anti-bot de YouTube. */
    private val throttleMs: Long,
    /** Première attente après un `BotDetected` ; doublée à chaque nouvel essai. */
    private val botBackoffMs: Long,
) {
    @Inject
    constructor(
        store: ImportStore,
        importDao: ImportDao,
        streamRepository: StreamRepository,
        trackCache: TrackCache,
        matcher: TrackMatcher,
    ) : this(store, importDao, streamRepository, trackCache, matcher, THROTTLE_MS, BOT_BACKOFF_MS)

    /**
     * Traite les titres restants du job [jobId]. [onProgress] est appelé après chaque titre avec le job à jour.
     * Un seul job est traité à la fois dans le process (verrou partagé) pour ne pas multiplier les requêtes.
     */
    suspend fun process(jobId: Long, onProgress: suspend (ImportJobEntity) -> Unit = {}): ImportOutcome =
        lock.withLock {
            if (importDao.job(jobId) == null) return@withLock ImportOutcome.Done
            for (item in importDao.pendingItems(jobId)) {
                currentCoroutineContext().ensureActive()
                val resolved = when (val resolution = resolveWithBackoff(item)) {
                    is Resolution.Interrupted -> return@withLock ImportOutcome.Retry(resolution.error)
                    is Resolution.Result -> resolution
                }
                val job = store.applyResult(item.id, resolved.result)
                    ?: return@withLock ImportOutcome.Done // job supprimé
                onProgress(job)
                if (resolved.networkUsed) delay(throttleMs)
            }
            store.complete(jobId)?.let { onProgress(it) }
            ImportOutcome.Done
        }

    private sealed interface Resolution {
        data class Result(val result: MatchResult, val networkUsed: Boolean) : Resolution
        data class Interrupted(val error: AppError) : Resolution
    }

    private suspend fun resolveWithBackoff(item: ImportItemEntity): Resolution {
        var botWaits = 0
        while (true) {
            try {
                return resolve(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppException) {
                when (e.error) {
                    AppError.BotDetected -> {
                        if (botWaits >= MAX_BOT_WAITS) return Resolution.Interrupted(e.error)
                        delay(botBackoffMs shl botWaits)
                        botWaits++
                    }
                    AppError.Network -> return Resolution.Interrupted(e.error)
                    else -> return Resolution.Result(NOT_FOUND, networkUsed = true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Import item ${item.id} failed", e)
                return Resolution.Result(NOT_FOUND, networkUsed = true)
            }
        }
    }

    private suspend fun resolve(item: ImportItemEntity): Resolution {
        val imported = item.toImportedTrack()
        val id = imported.youtubeId
        return if (id != null) resolveKnownId(id, imported) else resolveBySearch(imported)
    }

    /** Id YouTube connu : pas de matching. Métadonnées depuis le cache local, sinon l'extracteur. */
    private suspend fun resolveKnownId(id: String, imported: ImportedTrack): Resolution {
        trackCache.get(id)?.let { return Resolution.Result(matched(it), networkUsed = false) }
        val track = try {
            streamRepository.track(id)
        } catch (e: AppException) {
            when (e.error) {
                AppError.BotDetected, AppError.Network -> throw e
                // Vidéo supprimée / privée : rien à ajouter.
                AppError.Unavailable -> return Resolution.Result(NOT_FOUND, networkUsed = true)
                // Métadonnées illisibles mais id valide : titre minimal, résolu à la lecture.
                else -> Track(id = id, title = imported.title.ifBlank { id }, artist = imported.primaryArtist.orEmpty())
            }
        }
        return Resolution.Result(matched(track), networkUsed = true)
    }

    private fun matched(track: Track) =
        MatchResult(MatchStatus.MATCHED, MatchCandidate(track, 1.0), emptyList())

    private suspend fun resolveBySearch(imported: ImportedTrack): Resolution {
        val query = matcher.buildQuery(imported)
        if (query.isBlank()) return Resolution.Result(NOT_FOUND, networkUsed = false)
        var result = matcher.match(imported, searchTracks(query, SearchFilter.SONGS))
        if (result.status == MatchStatus.NOT_FOUND) {
            delay(throttleMs)
            val fallback = matcher.match(imported, searchTracks(query, SearchFilter.VIDEOS))
            if ((fallback.best?.score ?: -1.0) > (result.best?.score ?: -1.0)) result = fallback
        }
        return Resolution.Result(result, networkUsed = true)
    }

    private suspend fun searchTracks(query: String, filter: SearchFilter): List<Track> =
        streamRepository.search(query, filter).items
            .filterIsInstance<SearchResult.TrackResult>()
            .map { it.track }
            .take(MAX_CANDIDATES)

    companion object {
        const val THROTTLE_MS = 300L
        const val BOT_BACKOFF_MS = 30_000L
        const val MAX_BOT_WAITS = 3
        private const val MAX_CANDIDATES = 10
        private const val TAG = "ImportProcessor"
        private val NOT_FOUND = MatchResult(MatchStatus.NOT_FOUND, null, emptyList())

        /** Verrou du process : un seul job d'import traité à la fois. */
        private val lock = Mutex()
    }
}
