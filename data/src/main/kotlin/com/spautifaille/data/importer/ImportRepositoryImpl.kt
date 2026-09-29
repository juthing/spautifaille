package com.spautifaille.data.importer

import android.content.Context
import androidx.room.withTransaction
import com.spautifaille.data.R
import com.spautifaille.data.local.ImportDao
import com.spautifaille.data.local.ImportItemEntity
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
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class ImportRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
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
            ?: throw AppException(AppError.Unknown(context.getString(R.string.data_import_error_source_not_handled)))
        val playlists = try {
            withContext(io) { importer.read(source) }
        } catch (e: ImportException) {
            // Message lisible produit par l'importer : transmis tel quel à l'UI via AppError.Unknown.detail.
            throw AppException(AppError.Unknown(e.message), e)
        }
        if (playlists.isEmpty()) {
            throw AppException(AppError.Unknown(context.getString(R.string.data_import_error_nothing_to_import)))
        }

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
        val base = sourceName.trim().ifEmpty { context.getString(R.string.data_import_default_playlist) }
        val taken = playlistRepository.observePlaylists().first().map { it.name.lowercase() }.toSet()
        if (base.lowercase() !in taken) return base
        val suffixed = "$base (import)"
        if (suffixed.lowercase() !in taken) return suffixed
        var n = 2
        while ("$base (import $n)".lowercase() in taken) n++
        return "$base (import $n)"
    }

    override fun observeJobs(): Flow<List<ImportJob>> =
        importDao.observeJobs().map { rows -> rows.map { it.toDomain() } }.distinctUntilChanged()

    override fun observeJob(jobId: Long): Flow<ImportJob?> =
        importDao.observeJob(jobId).map { it?.toDomain() }.distinctUntilChanged()

    /**
     * Chaque mise à jour d'une ligne relance la requête Room sur tout le job. Pour ne pas tout reconstruire, on garde
     * (par collecteur) l'item du domaine déjà calculé de chaque ligne inchangée : seules les lignes modifiées relisent
     * les titres candidats et sont reconverties ; les autres gardent la même instance (égalité et clés stables en UI).
     */
    override fun observeItems(jobId: Long): Flow<List<ImportItem>> = flow {
        val cache = HashMap<Long, Pair<ImportItemEntity, ImportItem>>()
        importDao.observeItems(jobId).collect { rows ->
            val changed = rows.filter { cache[it.id]?.first != it }
            val ids = changed.flatMap { it.candidateIds() }.distinct()
            // Requêtes par lots : SQLite limite le nombre de paramètres liés.
            val tracks = ids.chunked(TRACK_QUERY_CHUNK).flatMap { trackDao.getAll(it) }.associateBy { it.id }
            val items = rows.map { row ->
                val cached = cache[row.id]
                if (cached != null && cached.first == row) {
                    cached.second
                } else {
                    row.toDomain(tracks).also { cache[row.id] = row to it }
                }
            }
            cache.keys.retainAll(rows.mapTo(HashSet()) { it.id })
            emit(items)
        }
    }.distinctUntilChanged().flowOn(io)

    override suspend fun resolveItem(itemId: Long, chosen: Track?) {
        store.resolve(itemId, chosen)
    }

    override suspend fun deleteJob(jobId: Long) {
        scheduler.cancel(jobId)
        importDao.deleteJob(jobId)
    }

    private companion object {
        const val TRACK_QUERY_CHUNK = 500
    }
}
