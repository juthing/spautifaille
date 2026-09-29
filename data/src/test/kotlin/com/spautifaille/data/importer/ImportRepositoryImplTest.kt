package com.spautifaille.data.importer

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.spautifaille.data.local.TEST_SDK
import com.spautifaille.domain.error.AppError
import com.spautifaille.domain.error.AppException
import com.spautifaille.domain.importer.ImportFormat
import com.spautifaille.domain.importer.ImportJobState
import com.spautifaille.domain.importer.ImportSource
import com.spautifaille.domain.importer.ImportedPlaylist
import com.spautifaille.domain.importer.ImportedTrack
import com.spautifaille.domain.importer.MatchStatus
import com.spautifaille.domain.model.Track
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [TEST_SDK])
class ImportRepositoryImplTest {

    private lateinit var env: ImportEnv
    private val source = ImportSource.File("content://x/y.csv", "y.csv", "text/csv")

    @Before
    fun setUp() {
        env = ImportEnv()
    }

    @After
    fun tearDown() = env.close()

    private fun repoWith(playlists: () -> List<ImportedPlaylist>) =
        env.repository(listOf(FakeImporter { playlists() }), UnconfinedTestDispatcher())

    private fun playlist(name: String, vararg titles: String, format: ImportFormat = ImportFormat.EXPORTIFY_CSV) =
        ImportedPlaylist(name, titles.map { imported(it) }, format)

    private fun perfect(id: String, title: String) = Track(id, title, "Artiste", durationMs = 200_000L)

    @Test
    fun `start creates the playlist, the job with pending items and schedules the work`() = runTest {
        val repo = repoWith { listOf(playlist("Road trip", "A", "B", "C")) }

        val ids = repo.start(source)

        assertEquals(1, ids.size)
        assertEquals(ids, env.scheduler.enqueued)
        val job = repo.observeJob(ids.single()).first()!!
        assertEquals("Road trip", job.playlistName)
        assertEquals(ImportFormat.EXPORTIFY_CSV, job.format)
        assertEquals(ImportJobState.RUNNING, job.state)
        assertEquals(listOf(3, 0, 0, 0, 0), listOf(job.total, job.processed, job.matched, job.needsReview, job.notFound))
        val target = env.playlists.observePlaylist(job.targetPlaylistId!!).first()!!
        assertEquals("Road trip", target.playlist.name)
        assertTrue(target.entries.isEmpty())
        val items = repo.observeItems(job.id).first()
        assertEquals(listOf("A", "B", "C"), items.map { it.source.title })
        assertEquals(listOf(0, 1, 2), items.map { it.position })
        assertTrue(items.all { it.result.status == MatchStatus.PENDING && it.result.best == null })
        assertEquals(listOf("Artiste"), items.first().source.artists)
        assertEquals(200_000L, items.first().source.durationMs)
    }

    @Test
    fun `multi playlist sources create one job each and existing names get an import suffix`() = runTest {
        env.playlists.create("Favoris")
        val repo = repoWith { listOf(playlist("Favoris", "A"), playlist("Favoris", "B"), playlist("Rock", "C")) }

        val ids = repo.start(source)

        assertEquals(3, ids.size)
        assertEquals(ids, env.scheduler.enqueued)
        val names = repo.observeJobs().first().map { it.playlistName }
        assertEquals(setOf("Favoris (import)", "Favoris (import 2)", "Rock"), names.toSet())
    }

    @Test
    fun `source artists with several values round trip`() = runTest {
        val multi = ImportedPlaylist(
            "Duos",
            listOf(ImportedTrack("Titre", listOf("A", "B"), album = "Alb", durationMs = 1_000, isrc = "X1", youtubeId = null)),
            ImportFormat.SPOTIFY_JSON,
        )
        val repo = repoWith { listOf(multi) }

        val id = repo.start(source).single()

        assertEquals(multi.tracks.single(), repo.observeItems(id).first().single().source)
    }

    @Test
    fun `import errors are surfaced as AppException with a readable detail and create nothing`() = runTest {
        val failing = env.repository(
            listOf(FakeImporter { throw ImportException("Format de fichier non reconnu.") }),
            UnconfinedTestDispatcher(),
        )

        try {
            failing.start(source)
            fail("exception attendue")
        } catch (e: AppException) {
            assertEquals(AppError.Unknown("Format de fichier non reconnu."), e.error)
        }
        assertTrue(env.scheduler.enqueued.isEmpty())
        assertTrue(env.db.importDao().observeJobs().first().isEmpty())
    }

    @Test
    fun `unsupported source is an error`() = runTest {
        val repo = env.repository(listOf(FakeImporter(handles = { false }) { emptyList() }), UnconfinedTestDispatcher())
        try {
            repo.start(source)
            fail("exception attendue")
        } catch (e: AppException) {
            assertTrue(e.error is AppError.Unknown)
        }
    }

    @Test
    fun `observeItems exposes best candidate and alternatives after processing`() = runTest {
        val repo = repoWith { listOf(playlist("P", "Song")) }
        val jobId = repo.start(source).single()
        env.stream.searchHandler = { _, _ ->
            listOf(perfect("best", "Song"), Track("alt", "Song (Live)", "Artiste", durationMs = 200_000L))
        }

        env.processor().process(jobId)

        val item = repo.observeItems(jobId).first().single()
        assertEquals(MatchStatus.MATCHED, item.result.status)
        assertEquals("best", item.result.best!!.track.id)
        assertTrue(item.result.best!!.score > 0.9)
        assertTrue(item.result.alternatives.all { it.track.id != "best" })
    }

    // --- resolveItem --------------------------------------------------------------------------------

    /** Job de 4 titres traités : 0 MATCHED (m0), 1 NEEDS_REVIEW (r1, alt a1), 2 NOT_FOUND, 3 MATCHED (m3). */
    private suspend fun processedJob(): Triple<ImportRepositoryImpl, Long, Long> {
        val repo = repoWith { listOf(playlist("P", "T0", "T1", "T2", "T3")) }
        val jobId = repo.start(source).single()
        env.stream.searchHandler = { query, _ ->
            when {
                "T0" in query -> listOf(perfect("m0", "T0"))
                "T1" in query -> listOf(Track("r1", "T1", "Zzzz Qqqq", durationMs = 200_000L), Track("a1", "T1", "Aaaa Bbbb", durationMs = 50_000L))
                "T3" in query -> listOf(perfect("m3", "T3"))
                else -> emptyList()
            }
        }
        env.processor().process(jobId)
        val playlistId = repo.observeJob(jobId).first()!!.targetPlaylistId!!
        return Triple(repo, jobId, playlistId)
    }

    @Test
    fun `processed job has the expected shape`() = runTest {
        val (repo, jobId, playlistId) = processedJob()
        val statuses = repo.observeItems(jobId).first().map { it.result.status }
        assertEquals(listOf(MatchStatus.MATCHED, MatchStatus.NEEDS_REVIEW, MatchStatus.NOT_FOUND, MatchStatus.MATCHED), statuses)
        assertEquals(listOf("m0", "r1", "m3"), env.playlistTrackIds(playlistId))
    }

    @Test
    fun `choosing an alternative replaces the entry in place`() = runTest {
        val (repo, jobId, playlistId) = processedJob()
        val review = repo.observeItems(jobId).first()[1]
        val alt = review.result.alternatives.firstOrNull()?.track ?: Track("a1", "T1", "Aaaa Bbbb", durationMs = 50_000L)

        repo.resolveItem(review.id, alt)

        assertEquals(listOf("m0", "a1", "m3"), env.playlistTrackIds(playlistId))
        assertEquals(listOf(0, 1, 2), env.playlistPositions(playlistId))
        val updated = repo.observeItems(jobId).first()[1]
        assertEquals(MatchStatus.MATCHED, updated.result.status)
        assertEquals("a1", updated.result.best!!.track.id)
        assertTrue(updated.result.alternatives.any { it.track.id == "r1" }) // l'ancien meilleur devient alternative
        val job = repo.observeJob(jobId).first()!!
        assertEquals(listOf(3, 0, 1), listOf(job.matched, job.needsReview, job.notFound))
    }

    @Test
    fun `validating the current best keeps the entry and counts it as matched`() = runTest {
        val (repo, jobId, playlistId) = processedJob()
        val review = repo.observeItems(jobId).first()[1]
        val entryBefore = env.db.importDao().item(review.id)!!.entryId

        repo.resolveItem(review.id, review.result.best!!.track)

        assertEquals(listOf("m0", "r1", "m3"), env.playlistTrackIds(playlistId))
        assertEquals(entryBefore, env.db.importDao().item(review.id)!!.entryId)
        val job = repo.observeJob(jobId).first()!!
        assertEquals(listOf(3, 0, 1), listOf(job.matched, job.needsReview, job.notFound))
    }

    @Test
    fun `excluding an item removes its entry`() = runTest {
        val (repo, jobId, playlistId) = processedJob()
        val review = repo.observeItems(jobId).first()[1]

        repo.resolveItem(review.id, null)

        assertEquals(listOf("m0", "m3"), env.playlistTrackIds(playlistId))
        assertEquals(listOf(0, 1), env.playlistPositions(playlistId))
        val updated = repo.observeItems(jobId).first()[1]
        assertEquals(MatchStatus.NOT_FOUND, updated.result.status)
        assertNull(updated.result.best)
        assertNull(env.db.importDao().item(review.id)!!.entryId)
        val job = repo.observeJob(jobId).first()!!
        assertEquals(listOf(2, 0, 2), listOf(job.matched, job.needsReview, job.notFound))
    }

    @Test
    fun `resolving a not found item inserts the track at its source position`() = runTest {
        val (repo, jobId, playlistId) = processedJob()
        val missing = repo.observeItems(jobId).first()[2]

        repo.resolveItem(missing.id, perfect("manual", "T2"))

        assertEquals(listOf("m0", "r1", "manual", "m3"), env.playlistTrackIds(playlistId))
        assertEquals(listOf(0, 1, 2, 3), env.playlistPositions(playlistId))
        val item = repo.observeItems(jobId).first()[2]
        assertEquals(MatchStatus.MATCHED, item.result.status)
        assertEquals("manual", item.result.best!!.track.id)
        val job = repo.observeJob(jobId).first()!!
        assertEquals(listOf(3, 1, 0), listOf(job.matched, job.needsReview, job.notFound))
    }

    @Test
    fun `re-picking after an exclusion restores the entry at the right place`() = runTest {
        val (repo, jobId, playlistId) = processedJob()
        val review = repo.observeItems(jobId).first()[1]
        repo.resolveItem(review.id, null)

        val excluded = repo.observeItems(jobId).first()[1]
        assertTrue(excluded.result.alternatives.isNotEmpty()) // candidats conservés pour un nouveau choix
        repo.resolveItem(excluded.id, excluded.result.alternatives.first().track)

        assertEquals(3, env.playlistTrackIds(playlistId).size)
        assertEquals("m0", env.playlistTrackIds(playlistId).first())
        assertEquals("m3", env.playlistTrackIds(playlistId).last())
    }

    @Test
    fun `deleteJob cancels the work and keeps the created playlist`() = runTest {
        val (repo, jobId, playlistId) = processedJob()

        repo.deleteJob(jobId)

        assertEquals(listOf(jobId), env.scheduler.cancelled)
        assertNull(repo.observeJob(jobId).first())
        assertTrue(env.db.importDao().items(jobId).isEmpty())
        assertEquals(listOf("m0", "r1", "m3"), env.playlistTrackIds(playlistId))
        assertNotNull(env.playlists.observePlaylist(playlistId).first())
    }

    @Test
    fun `deleting the target playlist keeps the job usable`() = runTest {
        val (repo, jobId, playlistId) = processedJob()
        env.playlists.delete(playlistId)

        val job = repo.observeJob(jobId).first()!!
        assertNull(job.targetPlaylistId)
        val review = repo.observeItems(jobId).first()[1]
        repo.resolveItem(review.id, null)
        assertEquals(MatchStatus.NOT_FOUND, repo.observeItems(jobId).first()[1].result.status)
        assertFalse(repo.observeJobs().first().isEmpty())
    }

    @Test
    fun `observeJobs emits updates as the job progresses`() = runTest {
        val repo = repoWith { listOf(playlist("P", "Song")) }
        val jobId = repo.start(source).single()
        env.stream.searchHandler = { _, _ -> listOf(perfect("x", "Song")) }

        repo.observeJob(jobId).test {
            assertEquals(0, awaitItem()!!.processed)
            env.processor().process(jobId)
            var last = awaitItem()!!
            while (last.state != ImportJobState.COMPLETED) last = awaitItem()!!
            assertEquals(1, last.matched)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
