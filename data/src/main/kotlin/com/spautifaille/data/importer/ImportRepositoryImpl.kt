package com.spautifaille.data.importer

import androidx.room.withTransaction
import com.spautifaille.data.local.ImportDao
import com.spautifaille.data.local.SpautifailleDatabase
import com.spautifaille.data.local.TrackDao
import com.spautifaille.domain.di.IoDispatcher
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.importer.ImportItem
import com.spautifaille.domain.importer.ImportJob
import com.spautifaille.domain.importer.ImportRepository
import com.spautifaille.domain.importer.ImportSource
import com.spautifaille.domain.importer.ImportedPlaylist
import com.spautifaille.domain.model.Track
import com.spautifaille.domain.repository.PlaylistRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class ImportRepositoryImpl @Inject constructor(
    private val db: SpautifailleDatabase,
    private val importDao: ImportDao,
    private val trackDao: TrackDao,
    private val playlistRepository: PlaylistRepository,
    private val importers: PlaylistImporters,
    private val store: ImportStore,
    private val scheduler: ImportScheduler,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ImportRepository {

    override suspend fun start(source: ImportSource): List<Long> {
        val importer = importers.find(source)
            ?: throw AppException(AppError.Unknown("Cette source n'est pas prise en charge."))
        val playlists = try {
            withContext(io) { importer.read(source) }
        } catch (e: ImportException) {
            // Message lisible produit par l'importer : transmis tel quel à l'UI via AppError.Unknown.detail.
            throw AppException(AppError.Unknown(e.message), e)
        }
        if (playlists.isEmpty()) throw AppException(AppError.Unknown("Aucune playlist à importer."))

        val jobIds = playlists.map { createJob(it) }
        // WorkManager n'est sollicité qu'une fois les jobs validés en base.
        jobIds.forEach(scheduler::enqueue)
        return jobIds
    }

    private suspend fun createJob(playlist: ImportedPlaylist): Long {
        val name = uniqueName(playlist.name)
        return db.withTransaction {
            val playlistId = playlistRepository.create(name)
            store.createJob(playlist.copy(name = name), playlistId)
        }
    }

    /** Nom de la playlist cible : celui de la source, suffixé « (import) » s'il est déjà pris. */
    private suspend fun uniqueName(sourceName: String): String {
        val base = sourceName.trim().ifEmpty { DEFAULT_NAME }
        val taken = playlistRepository.observePlaylists().first().map { it.name.lowercase() }.toSet()
        if (base.lowercase() !in taken) return base
        val suffixed = "$base (import)"
        if (suffixed.lowercase() !in taken) return suffixed
        var n = 2
        while ("$base (import $n)".lowercase() in taken) n++
        return "$base (import $n)"
    }

    override fun observeJobs(): Flow<List<ImportJob>> =
        importDao.observeJobs().map { rows -> rows.map { it.toDomain() } }

    override fun observeJob(jobId: Long): Flow<ImportJob?> =
        importDao.observeJob(jobId).map { it?.toDomain() }

    override fun observeItems(jobId: Long): Flow<List<ImportItem>> =
        importDao.observeItems(jobId).map { rows ->
            val ids = rows.flatMap { it.candidateIds() }.distinct()
            // Requêtes par lots : SQLite limite le nombre de paramètres liés.
            val tracks = ids.chunked(TRACK_QUERY_CHUNK).flatMap { trackDao.getAll(it) }.associateBy { it.id }
            rows.map { it.toDomain(tracks) }
        }.flowOn(io)

    override suspend fun resolveItem(itemId: Long, chosen: Track?) {
        store.resolve(itemId, chosen)
    }

    override suspend fun deleteJob(jobId: Long) {
        scheduler.cancel(jobId)
        importDao.deleteJob(jobId)
    }

    private companion object {
        const val DEFAULT_NAME = "Playlist importée"
        const val TRACK_QUERY_CHUNK = 500
    }
}
