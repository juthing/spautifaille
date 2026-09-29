package com.spautifaille.ui.importer

import com.spautifaille.domain.importer.ImportFormat
import com.spautifaille.domain.importer.ImportItem
import com.spautifaille.domain.importer.ImportJob
import com.spautifaille.domain.importer.ImportJobState
import com.spautifaille.domain.importer.ImportRepository
import com.spautifaille.domain.importer.ImportSource
import com.spautifaille.domain.importer.ImportedTrack
import com.spautifaille.domain.importer.MatchCandidate
import com.spautifaille.domain.importer.MatchResult
import com.spautifaille.domain.importer.MatchStatus
import com.spautifaille.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** `Dispatchers.Main` non confiné : les `viewModelScope.launch` s'exécutent immédiatement. */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportMainRule : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(UnconfinedTestDispatcher())
    override fun finished(description: Description) = Dispatchers.resetMain()
}

@OptIn(ExperimentalCoroutinesApi::class)
fun <T> TestScope.collectInBackground(flow: Flow<T>): Job =
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }

class FakeImportRepository : ImportRepository {
    val jobs = MutableStateFlow<List<ImportJob>>(emptyList())
    val items = MutableStateFlow<Map<Long, List<ImportItem>>>(emptyMap())

    val started = mutableListOf<ImportSource>()
    val resolved = mutableListOf<Pair<Long, Track?>>()
    val deleted = mutableListOf<Long>()

    /** Appelé par [start] ; peut lever pour simuler un échec. */
    var onStart: suspend (ImportSource) -> List<Long> = { listOf(1L) }
    var resolveError: Throwable? = null

    override suspend fun start(source: ImportSource): List<Long> {
        started += source
        return onStart(source)
    }

    override fun observeJobs(): Flow<List<ImportJob>> = jobs
    override fun observeJob(jobId: Long): Flow<ImportJob?> = jobs.map { list -> list.firstOrNull { it.id == jobId } }
    override fun observeItems(jobId: Long): Flow<List<ImportItem>> = items.map { it[jobId].orEmpty() }

    override suspend fun resolveItem(itemId: Long, chosen: Track?) {
        resolveError?.let { throw it }
        resolved += itemId to chosen
    }

    override suspend fun deleteJob(jobId: Long) {
        deleted += jobId
    }
}

fun job(
    id: Long = 1,
    state: ImportJobState = ImportJobState.COMPLETED,
    total: Int = 10,
    processed: Int = total,
    matched: Int = 8,
    needsReview: Int = 1,
    notFound: Int = 1,
    playlistId: Long? = 100,
) = ImportJob(id, "Playlist $id", ImportFormat.EXPORTIFY_CSV, state, total, processed, matched, needsReview, notFound, playlistId, null, 0L)

fun tr(id: String, title: String = "Titre $id", artist: String = "Artiste") =
    Track(id = id, title = title, artist = artist, durationMs = 200_000L)

fun item(
    id: Long,
    status: MatchStatus,
    best: Track? = null,
    score: Double = 0.7,
    alternatives: List<Track> = emptyList(),
    jobId: Long = 1,
    source: ImportedTrack = ImportedTrack("Source $id", listOf("Artiste"), durationMs = 200_000L),
) = ImportItem(
    id = id,
    jobId = jobId,
    position = id.toInt(),
    source = source,
    result = MatchResult(status, best?.let { MatchCandidate(it, score) }, alternatives.map { MatchCandidate(it, 0.5) }),
)
