package com.spautifaille.data.importer

import androidx.room.withTransaction
import com.spautifaille.data.local.ImportDao
import com.spautifaille.data.local.ImportItemEntity
import com.spautifaille.data.local.ImportJobEntity
import com.spautifaille.data.local.PlaylistDao
import com.spautifaille.data.local.PlaylistEntryEntity
import com.spautifaille.data.local.SpautifailleDatabase
import com.spautifaille.data.local.TrackDao
import com.spautifaille.data.local.toEntity
import com.spautifaille.domain.importer.ImportJobState
import com.spautifaille.domain.importer.ImportedPlaylist
import com.spautifaille.domain.importer.MatchResult
import com.spautifaille.domain.importer.MatchStatus
import com.spautifaille.domain.model.Track
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Accès transactionnel aux jobs d'import, partagé par le worker ([ImportProcessor]) et par
 * [ImportRepositoryImpl] (choix manuels). Toutes les écritures « item + entrée de playlist + compteurs »
 * se font dans une seule transaction : une reprise après arrêt du process ne peut donc ni doubler ni perdre d'entrée.
 */
@Singleton
class ImportStore internal constructor(
    private val db: SpautifailleDatabase,
    private val importDao: ImportDao,
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val clock: () -> Long,
) {
    @Inject
    constructor(
        db: SpautifailleDatabase,
        importDao: ImportDao,
        trackDao: TrackDao,
        playlistDao: PlaylistDao,
    ) : this(db, importDao, trackDao, playlistDao, System::currentTimeMillis)

    /** Crée le job (RUNNING) et ses items (PENDING). Renvoie l'id du job. */
    suspend fun createJob(playlist: ImportedPlaylist, targetPlaylistId: Long): Long = db.withTransaction {
        val jobId = importDao.insertJob(
            ImportJobEntity(
                playlistName = playlist.name,
                format = playlist.sourceFormat.name,
                state = ImportJobState.RUNNING.name,
                total = playlist.tracks.size,
                processed = 0,
                matched = 0,
                needsReview = 0,
                notFound = 0,
                targetPlaylistId = targetPlaylistId,
                error = null,
                createdAt = clock(),
            ),
        )
        importDao.insertItems(playlist.tracks.mapIndexed { index, t -> t.toItemEntity(jobId, index) })
        jobId
    }

    /**
     * Enregistre le résultat du matching d'un item encore `PENDING` : titres candidats, item, entrée de playlist
     * (MATCHED / NEEDS_REVIEW) et compteurs. Renvoie le job mis à jour, ou `null` si l'item ou le job n'existe plus
     * (job supprimé pendant le traitement).
     */
    suspend fun applyResult(itemId: Long, result: MatchResult): ImportJobEntity? = db.withTransaction {
        val item = importDao.item(itemId) ?: return@withTransaction null
        val job = importDao.job(item.jobId) ?: return@withTransaction null
        if (item.status != MatchStatus.PENDING.name) return@withTransaction job // déjà traité (choix manuel)

        val now = clock()
        val candidates = listOfNotNull(result.best) + result.alternatives
        upsertTracks(candidates.map { it.track }, now)

        val best = result.best
        val addToPlaylist = best != null && job.targetPlaylistId != null &&
            (result.status == MatchStatus.MATCHED || result.status == MatchStatus.NEEDS_REVIEW)
        val entryId = if (addToPlaylist) {
            insertEntry(job.targetPlaylistId, item, best.track.id, now)
        } else {
            null
        }
        importDao.updateItem(
            item.copy(
                status = result.status.name,
                bestTrackId = best?.track?.id,
                bestScore = best?.score,
                candidates = encodeCandidates(candidates.map { CandidateJson(it.track.id, it.score) }),
                entryId = entryId,
            ),
        )
        refreshCounters(job)
    }

    /** Passe le job en COMPLETED (sans effet s'il n'existe plus). */
    suspend fun complete(jobId: Long): ImportJobEntity? = setState(jobId, ImportJobState.COMPLETED, null)

    suspend fun fail(jobId: Long, error: String): ImportJobEntity? = setState(jobId, ImportJobState.FAILED, error)

    private suspend fun setState(jobId: Long, state: ImportJobState, error: String?): ImportJobEntity? =
        db.withTransaction {
            val job = importDao.job(jobId) ?: return@withTransaction null
            val updated = refreshCounters(job).copy(state = state.name, error = error)
            importDao.updateJob(updated)
            updated
        }

    /**
     * Choix manuel : [chosen] devient le titre retenu (MATCHED) ou, si `null`, l'item est exclu (NOT_FOUND) et son
     * entrée retirée de la playlist. Le titre remplace l'entrée existante à la même position.
     */
    suspend fun resolve(itemId: Long, chosen: Track?) {
        db.withTransaction {
            val item = importDao.item(itemId) ?: return@withTransaction
            val job = importDao.job(item.jobId) ?: return@withTransaction
            val now = clock()
            val playlistId = job.targetPlaylistId
            val previous = decodeCandidates(item.candidates)

            var newEntryId: Long? = item.entryId
            if (chosen != null) upsertTracks(listOf(chosen), now)
            if (playlistId != null) {
                val oldEntryId = item.entryId
                val oldPosition = oldEntryId?.let { playlistDao.positionOf(playlistId, it) }
                when {
                    chosen == null -> {
                        if (oldEntryId != null && oldPosition != null) playlistDao.removeEntry(playlistId, oldEntryId, now)
                        newEntryId = null
                    }
                    oldPosition != null && item.bestTrackId == chosen.id &&
                        item.status != MatchStatus.NOT_FOUND.name -> Unit // déjà en place : simple validation
                    oldPosition != null -> {
                        playlistDao.removeEntry(playlistId, oldEntryId, now)
                        newEntryId = insertEntryAt(playlistId, chosen.id, oldPosition, now)
                    }
                    else -> newEntryId = insertEntry(playlistId, item, chosen.id, now)
                }
            }

            val score = chosen?.let { c -> previous.firstOrNull { it.trackId == c.id }?.score ?: MANUAL_SCORE }
            val reordered = if (chosen == null || score == null) {
                previous
            } else {
                listOf(CandidateJson(chosen.id, score)) + previous.filter { it.trackId != chosen.id }
            }
            importDao.updateItem(
                item.copy(
                    status = (if (chosen != null) MatchStatus.MATCHED else MatchStatus.NOT_FOUND).name,
                    bestTrackId = chosen?.id,
                    bestScore = score,
                    candidates = if (reordered.isEmpty()) item.candidates else encodeCandidates(reordered),
                    entryId = newEntryId,
                ),
            )
            refreshCounters(job)
        }
    }

    // --- internals ---------------------------------------------------------------------------------

    private suspend fun upsertTracks(tracks: List<Track>, now: Long) {
        if (tracks.isEmpty()) return
        trackDao.upsertAll(tracks.distinctBy { it.id }.map { it.toEntity(now) })
    }

    /** Recalcule les compteurs depuis les statuts des items (jamais de dérive) et enregistre le job. */
    private suspend fun refreshCounters(job: ImportJobEntity): ImportJobEntity {
        val pending = importDao.countByStatus(job.id, MatchStatus.PENDING.name)
        val updated = job.copy(
            processed = (job.total - pending).coerceAtLeast(0),
            matched = importDao.countByStatus(job.id, MatchStatus.MATCHED.name),
            needsReview = importDao.countByStatus(job.id, MatchStatus.NEEDS_REVIEW.name),
            notFound = importDao.countByStatus(job.id, MatchStatus.NOT_FOUND.name),
        )
        importDao.updateJob(updated)
        return updated
    }

    /**
     * Insère [trackId] dans la playlist à la place qui respecte l'ordre de la source : juste après l'entrée du titre
     * importé précédent, sinon juste avant celle du suivant, sinon en fin de playlist.
     */
    private suspend fun insertEntry(playlistId: Long, item: ImportItemEntity, trackId: String, now: Long): Long {
        val position = importDao.positionOfPrecedingEntry(item.jobId, playlistId, item.position)?.plus(1)
            ?: importDao.positionOfFollowingEntry(item.jobId, playlistId, item.position)
            ?: (playlistDao.maxPosition(playlistId) + 1)
        return insertEntryAt(playlistId, trackId, position, now)
    }

    private suspend fun insertEntryAt(playlistId: Long, trackId: String, position: Int, now: Long): Long {
        val last = playlistDao.maxPosition(playlistId)
        val target = position.coerceIn(0, last + 1)
        if (target <= last) playlistDao.shiftDown(playlistId, target, last)
        val entryId = importDao.insertEntry(
            PlaylistEntryEntity(playlistId = playlistId, trackId = trackId, position = target, addedAt = now),
        )
        playlistDao.touch(playlistId, now)
        return entryId
    }

    private companion object {
        /** Score attribué à un titre choisi hors des candidats (recherche manuelle). */
        const val MANUAL_SCORE = 1.0
    }
}
